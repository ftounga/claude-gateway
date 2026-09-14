package fr.claudegateway.governance.control;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * <b>Les promotions reportées faute de poste</b> (F-93 / SF-93-04, persistées en SF-93-05).
 *
 * <p>Quand la machine ne répond pas, un contrôle de fin de tour qui exige d'écrire dans la carte du
 * poste ne peut rien obtenir : refuser la clôture ferait redemander trois fois une écriture
 * impossible. Le contrôle <b>reporte</b> donc — et ce registre garde ce qui a été reporté, pour que
 * la dette soit <b>réclamée</b> au premier tour où le poste répond de nouveau. Un report qu'on ne
 * réclame jamais serait un effacement.</p>
 *
 * <p><b>Il survit désormais au redémarrage</b> (SF-93-05). En SF-93-04 le registre vivait en mémoire
 * du processus, comme {@code IntegriteMemo} ou {@code JugeMemo} : un redémarrage ou un autre pod
 * l'oubliait. Cette classe n'est plus qu'une <b>façade</b> — elle porte les constantes, la mesure du
 * temps et la mise en forme de la réclamation — et délègue à un {@link PromotionReporteeStore} : en
 * production le store persiste en base ({@link JpaPromotionReporteeStore}), en test il tient en
 * mémoire ({@link InMemoryPromotionReporteeStore}).</p>
 *
 * <p><b>Isolation.</b> La clé est le triple {@code (userId, hostId, workspaceId)} : l'utilisateur
 * propriétaire (déjà vérifié possédé par F-50), le <b>poste</b> hors ligne, et le projet. Un report
 * n'est jamais réclamé ailleurs.</p>
 */
@Component
public class PromotionReportee {

    /** La mention dite à la clôture d'un tour reporté. Texte stable : il est lu par l'utilisateur. */
    public static final String NOTICE = "promotion reportée : poste hors ligne";

    /** La phrase complète ajoutée à la réponse finale. */
    public static final String NOTICE_SENTENCE = "*" + NOTICE + "* — la carte du poste n'a pas pu être "
            + "écrite pendant ce tour ; la promotion reste due et sera réclamée au premier tour où le "
            + "poste répondra.";

    /** Entrées retenues. Au-delà, la moins récemment reportée sort. */
    public static final int MAX_ENTRIES = 500;

    /** Éléments retenus par entrée : même borne que la citation d'un marqueur. */
    public static final int MAX_ELEMENTS = FinDeTourMarker.MAX_CITED;

    /** Durée de vie d'un report : au-delà, le rappel nominatif n'a plus de sens. */
    public static final Duration TTL = Duration.ofDays(7);

    /**
     * Ce qui a été reporté.
     *
     * @param elements   les éléments durables non rangés, sans doublon, bornés
     * @param dette      la dette déclarée la plus haute des tours reportés
     * @param reportedAt le premier report encore dû
     */
    public record Report(List<String> elements, int dette, Instant reportedAt) {

        public Report {
            elements = elements == null ? List.of() : List.copyOf(elements);
        }
    }

    private final PromotionReporteeStore store;

    /** Forme d'avant SF-93-05 : un registre en mémoire du processus (tests, repli). */
    public PromotionReportee() {
        this(new InMemoryPromotionReporteeStore());
    }

    /** Registre en mémoire avec une horloge maîtrisée (tests de durée de vie). */
    PromotionReportee(java.time.Clock clock) {
        this(new InMemoryPromotionReporteeStore(clock));
    }

    @Autowired
    public PromotionReportee(PromotionReporteeStore store) {
        this.store = store;
    }

    /**
     * Retient un report. Les reports successifs du même triple se <b>cumulent</b> : éléments sans
     * doublon, dette la plus haute, date du premier report.
     */
    public void reporter(UUID userId, UUID hostId, UUID workspaceId, Collection<String> elements,
            int dette) {
        if (userId == null || hostId == null || workspaceId == null) {
            return;
        }
        store.reporter(userId, hostId, workspaceId, elements, dette);
    }

    /**
     * <b>Réclame</b> le report d'un triple : le rend, et le retire. Réclamé une seule fois (D3).
     */
    public Optional<Report> reclamer(UUID userId, UUID hostId, UUID workspaceId) {
        if (userId == null || hostId == null || workspaceId == null) {
            return Optional.empty();
        }
        return store.reclamer(userId, hostId, workspaceId);
    }

    /** Vrai si un report est dû pour ce triple (sans le réclamer). */
    public boolean estDue(UUID userId, UUID hostId, UUID workspaceId) {
        if (userId == null || hostId == null || workspaceId == null) {
            return false;
        }
        return store.estDue(userId, hostId, workspaceId);
    }

    /**
     * L'action corrective de la réclamation : ce qui a été reporté, depuis quand, et <b>le geste</b>
     * — ranger ce qui est encore durable, écarter ce qui ne l'est plus.
     *
     * @param cited les fichiers de la carte du poste, déjà cités
     */
    public static String reclamation(Report report, String cited) {
        StringBuilder text = new StringBuilder("le poste était hors ligne au tour du ")
                .append(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm 'UTC'")
                        .withZone(java.time.ZoneOffset.UTC).format(report.reportedAt()))
                .append(" et la promotion a été reportée");
        if (!report.elements().isEmpty()) {
            text.append(" : ").append(String.join(", ", report.elements()));
        }
        if (report.dette() > 0) {
            text.append(report.elements().isEmpty() ? " : " : " ; ")
                    .append("dette déclarée ").append(report.dette())
                    .append(report.dette() > 1 ? " cases « - [ ] »" : " case « - [ ] »");
        }
        return text.append(". Le poste répond de nouveau : range chaque élément encore durable dans la "
                + "carte du poste (").append(cited).append("), trace-le coché dans STATE.md "
                + "« - [x] <élément> -> promu dans <fichier> » — ou écarte-le s'il ne l'est plus —, "
                + "puis reprends ta réponse avec le marqueur de fin de tour.").toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Aides partagées par les deux stores : une seule sémantique de fusion et de forme stockée.
    // ---------------------------------------------------------------------------------------------

    /**
     * Fusionne les éléments d'un report existant et ceux d'un nouveau report : sans doublon, dans
     * l'ordre, bornés à {@link #MAX_ELEMENTS}, chacun {@code strip()}é.
     */
    static List<String> mergeElements(List<String> previous, Collection<String> incoming) {
        Set<String> merged = new LinkedHashSet<>(previous == null ? List.of() : previous);
        if (incoming != null) {
            for (String element : incoming) {
                String item = element == null ? "" : element.strip();
                if (!item.isEmpty() && merged.size() < MAX_ELEMENTS) {
                    merged.add(item);
                }
            }
        }
        return new ArrayList<>(merged);
    }

    /** La dette retenue au cumul : la plus haute, jamais négative. */
    static int mergedDette(int previous, int incoming) {
        return Math.max(Math.max(0, incoming), Math.max(0, previous));
    }

    /**
     * Forme stockée à plat des éléments : un par ligne, les sauts internes remplacés par une espace,
     * les blancs écartés. {@code null} si rien à stocker.
     */
    static String joinElements(List<String> elements) {
        if (elements == null || elements.isEmpty()) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (String element : elements) {
            String item = element == null ? "" : element.strip().replaceAll("\\s*[\\r\\n]+\\s*", " ");
            if (item.isEmpty()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(item);
        }
        return joined.length() == 0 ? null : joined.toString();
    }

    /** Éclate la forme stockée : jamais {@code null}, sans blancs, sans doublon, borné. */
    static List<String> splitElements(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : stored.split("\n")) {
            String item = part.strip();
            if (!item.isEmpty() && !out.contains(item) && out.size() < MAX_ELEMENTS) {
                out.add(item);
            }
        }
        return List.copyOf(out);
    }
}
