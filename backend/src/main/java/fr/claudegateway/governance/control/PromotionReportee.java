package fr.claudegateway.governance.control;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * <b>Les promotions reportées faute de poste</b> (F-93 / SF-93-04).
 *
 * <p>Quand la machine ne répond pas, un contrôle de fin de tour qui exige d'écrire dans la carte du
 * poste ne peut rien obtenir : refuser la clôture ferait redemander trois fois une écriture
 * impossible. Le contrôle <b>reporte</b> donc — et ce registre garde ce qui a été reporté, pour que
 * la dette soit <b>réclamée</b> au premier tour où le poste répond de nouveau. Un report qu'on ne
 * réclame jamais serait un effacement.</p>
 *
 * <p><b>Ce n'est pas une garantie, et c'est assumé</b> — même choix que {@code IntegriteMemo} et
 * {@code JugeMemo} : le registre vit en mémoire du processus. Un redémarrage ou un autre pod
 * l'oublie. Le prix est borné : la dette physique (les cases {@code - [ ]} de {@code STATE.md}) reste
 * sur la machine et sera recomptée par le marqueur ; seul le rappel nominatif est perdu. En faire une
 * table ajouterait une migration et une écriture à chaque tour hors ligne.</p>
 *
 * <p><b>Isolation.</b> La clé est le couple {@code (userId, workspaceId)} du contexte de F-50, déjà
 * vérifié possédé : un report n'est jamais réclamé chez quelqu'un d'autre, ni sur un autre projet.</p>
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

    private record Key(UUID userId, UUID workspaceId) {
    }

    /**
     * Ce qui a été reporté.
     *
     * @param elements  les éléments durables non rangés, sans doublon, bornés
     * @param dette     la dette déclarée la plus haute des tours reportés
     * @param reportedAt le premier report encore dû
     */
    public record Report(List<String> elements, int dette, Instant reportedAt) {

        public Report {
            elements = elements == null ? List.of() : List.copyOf(elements);
        }
    }

    private final Map<Key, Report> entries = new LinkedHashMap<>();
    private final Clock clock;

    public PromotionReportee() {
        this(Clock.systemUTC());
    }

    PromotionReportee(Clock clock) {
        this.clock = clock;
    }

    /**
     * Retient un report. Les reports successifs du même projet se <b>cumulent</b> : éléments sans
     * doublon, dette la plus haute, date du premier report.
     */
    public synchronized void reporter(UUID userId, UUID workspaceId, Collection<String> elements,
            int dette) {
        if (userId == null || workspaceId == null) {
            return;
        }
        Instant now = clock.instant();
        purge(now);
        Key key = new Key(userId, workspaceId);
        Report previous = entries.remove(key);
        Set<String> merged = new LinkedHashSet<>(previous == null ? List.of() : previous.elements());
        if (elements != null) {
            for (String element : elements) {
                String item = element == null ? "" : element.strip();
                if (!item.isEmpty() && merged.size() < MAX_ELEMENTS) {
                    merged.add(item);
                }
            }
        }
        int kept = Math.max(Math.max(0, dette), previous == null ? 0 : previous.dette());
        entries.put(key, new Report(new ArrayList<>(merged), kept,
                previous == null ? now : previous.reportedAt()));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.keySet().iterator().next());
        }
    }

    /**
     * <b>Réclame</b> le report de ce projet : le rend, et le retire. Réclamé une seule fois — le
     * réclamer à chaque tour recréerait la boucle, à l'échelle des tours (D3).
     */
    public synchronized Optional<Report> reclamer(UUID userId, UUID workspaceId) {
        if (userId == null || workspaceId == null) {
            return Optional.empty();
        }
        purge(clock.instant());
        return Optional.ofNullable(entries.remove(new Key(userId, workspaceId)));
    }

    /** Vrai si un report est dû pour ce projet (sans le réclamer). */
    public synchronized boolean estDue(UUID userId, UUID workspaceId) {
        if (userId == null || workspaceId == null) {
            return false;
        }
        purge(clock.instant());
        return entries.containsKey(new Key(userId, workspaceId));
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

    private void purge(Instant now) {
        entries.values().removeIf(report -> report.reportedAt().plus(TTL).isBefore(now));
    }
}
