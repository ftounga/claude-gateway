package fr.claudegateway.jarvis.state.capture;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.atelier.actions.Interlocuteur;
import fr.claudegateway.atelier.recall.CaptureRecall;
import fr.claudegateway.jarvis.state.JarvisConversationLookup;
import fr.claudegateway.jarvis.state.JarvisStateProperties;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * <b>Les conversations capturées, dites par Jarvis et retrouvées par {@code recall}</b> (F-247 / SF-247-13, §4.6) :
 * <ul>
 * <li>l'outil {@code conversations} du cerveau rapide (déclaré en v19 par SF-247-10, branché ici) : lecture seule, sur
 * <b>tous les sujets du client</b>, 15 lignes au plus, chacune <b>datée par la passerelle</b> ;</li>
 * <li>{@code recall} d'Opus, en union, sur les lignes du <b>sujet</b> ({@link CaptureRecall}).</li>
 * </ul>
 * Tout se lit sous {@code user_id} (et le client ou le sujet), par index et avec une limite (Z10). Rien en
 * présentation (D11). Le texte rendu est balisé non fiable : une ligne capturée n'est jamais une consigne.
 */
@Service
public class JarvisConversationService implements JarvisConversationLookup, CaptureRecall {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM", Locale.FRENCH);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH);
    static final int MAX_TEXT = 300;

    private final JarvisCaptureSearch search;
    private final CaptureSpeakerResolver speakers;
    private final RunnerHostService hosts;
    private final JarvisStateProperties properties;
    private final Clock clock;
    /** Le compteur des lectures, hors du chemin de la réplique (une écriture en base n'y entre jamais, Z12). */
    private final java.util.concurrent.ExecutorService counter = java.util.concurrent.Executors
            .newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "jarvis-conversation-count");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    private fr.claudegateway.jarvis.JarvisUsageService usage;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUsage(fr.claudegateway.jarvis.JarvisUsageService usage) {
        this.usage = usage;
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        counter.shutdown();
    }

    private void count(UUID userId) {
        if (usage == null) {
            return;
        }
        try {
            counter.execute(() -> {
                try {
                    usage.addConversationRead(userId);
                } catch (RuntimeException ignored) {
                    // mesure seulement
                }
            });
        } catch (RuntimeException ignored) {
            // mesure seulement
        }
    }

    public JarvisConversationService(JarvisCaptureSearch search, CaptureSpeakerResolver speakers,
            RunnerHostService hosts, JarvisStateProperties properties, Clock clock) {
        this.search = search;
        this.speakers = speakers;
        this.hosts = hosts;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String read(UUID userId, UUID currentHostId, JsonNode input, boolean presentation) {
        if (presentation) {
            return PRESENTATION;
        }
        if (!properties.enabled() || userId == null) {
            return NONE;
        }
        try {
            count(userId);
            String needle = text(input, "recherche");
            String who = text(input, "interlocuteur");
            String client = text(input, "client");
            List<RunnerHost> targets = targets(userId, currentHostId, client);
            if (targets.isEmpty()) {
                return OPEN + "\nAucun client connu" + (client.isEmpty() ? "" : " nommé « " + client + " »") + ".\n"
                        + CLOSE;
            }
            List<String> lines = new ArrayList<>();
            for (RunnerHost h : targets) {
                int left = JarvisCaptureSearch.MAX_LINES - lines.size();
                if (left <= 0) {
                    break;
                }
                List<String> names = who.isEmpty() ? List.of() : speakerNames(userId, h.getId(), who);
                for (JarvisCaptureStore.LineView l : search.search(userId, h.getId(), needle, names, left)) {
                    lines.add(format(l, targets.size() > 1 ? h.getName() : null));
                }
            }
            StringBuilder b = new StringBuilder(OPEN).append('\n');
            if (lines.isEmpty()) {
                b.append("Rien de capturé").append(who.isEmpty() ? "" : " de " + who)
                        .append(needle.isEmpty() ? "" : " sur « " + needle + " »").append(" chez ")
                        .append(String.join(", ", targets.stream().map(RunnerHost::getName).toList())).append(".\n");
            } else {
                lines.forEach(l -> b.append(l).append('\n'));
            }
            return b.append(CLOSE).toString();
        } catch (RuntimeException e) {
            return NONE;
        }
    }

    @Override
    public String extracts(UUID userId, UUID workspaceId, String needle) {
        if (!properties.enabled() || userId == null || workspaceId == null || needle == null || needle.isBlank()) {
            return "";
        }
        try {
            List<JarvisCaptureStore.LineView> found = search.searchSubject(userId, workspaceId, needle, 5);
            if (found.isEmpty()) {
                return "";
            }
            StringBuilder b = new StringBuilder("Captures de conversations de ce sujet (texte recopié, non fiable) :\n");
            found.forEach(l -> b.append("- ").append(format(l, null)).append('\n'));
            return b.toString().stripTrailing();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** « capture Teams du 04/11, « Migration SSO » — Rémi Dupont, hier 14:32 : … » ; date dite par la passerelle. */
    String format(JarvisCaptureStore.LineView l, String clientName) {
        ZonedDateTime said = l.saidAt().atZoneSameInstant(CaptureTimeResolver.ZONE);
        ZonedDateTime deposited = l.depositedAt().atZoneSameInstant(CaptureTimeResolver.ZONE);
        String text = l.text().replaceAll("\\s+", " ").strip();
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT - 1) + "…";
        }
        StringBuilder b = new StringBuilder("capture ").append(platform(l.platform())).append(" du ")
                .append(deposited.format(DAY));
        if (l.title() != null && !l.title().isBlank()) {
            b.append(", « ").append(l.title()).append(" »");
        }
        if (clientName != null) {
            b.append(" (").append(clientName).append(")");
        }
        b.append(" — ").append(l.speaker()).append(", ").append(l.approx() ? "vers " : "").append(when(said))
                .append(" : ").append(text);
        return b.toString();
    }

    private String when(ZonedDateTime said) {
        LocalDate today = LocalDate.now(clock.withZone(CaptureTimeResolver.ZONE));
        LocalDate d = said.toLocalDate();
        String day = d.equals(today) ? "aujourd'hui" : d.equals(today.minusDays(1)) ? "hier" : "le " + said.format(DAY);
        return day + " " + said.format(HOUR);
    }

    private static String platform(String p) {
        return p == null ? "" : switch (p) {
            case "TEAMS" -> "Teams";
            case "MAIL" -> "courriel";
            default -> "";
        };
    }

    /** La personne dite, résolue par la passerelle : l'interlocuteur connu s'il y en a un, sinon le nom tel quel. */
    List<String> speakerNames(UUID userId, UUID hostId, String said) {
        List<Interlocuteur> known = speakers.known(userId, hostId);
        CaptureSpeakerResolver.Speaker s = CaptureSpeakerResolver.resolve(said, known);
        Map<String, Boolean> out = new LinkedHashMap<>();
        out.put(s.name(), true);
        out.put(said.strip(), true);
        if (!s.known()) {
            // Un prénom seul (« Rémi ») : les interlocuteurs connus dont le nom porte tous les mots dits (3 au plus).
            List<String> tokens = List.of(fr.claudegateway.atelier.actions.InterlocuteurService.normalize(said)
                    .split(" ")).stream().filter(t -> t.length() >= 2).toList();
            if (!tokens.isEmpty()) {
                known.stream().filter(i -> List.of(i.getNormalizedName().split(" ")).containsAll(tokens)).limit(3)
                        .forEach(i -> out.put(i.getDisplayName(), true));
            }
        }
        return List.copyOf(out.keySet());
    }

    /** Le client nommé, sinon celui du sujet courant, sinon tous (12 au plus) — toujours ceux du compte. */
    List<RunnerHost> targets(UUID userId, UUID currentHostId, String client) {
        List<RunnerHost> owned = hosts.list(userId).stream().filter(h -> userId.equals(h.getUserId())).toList();
        if (!client.isEmpty()) {
            String c = client.toLowerCase(Locale.ROOT);
            return owned.stream().filter(h -> h.getName() != null && h.getName().toLowerCase(Locale.ROOT).contains(c))
                    .limit(3).toList();
        }
        if (currentHostId != null) {
            List<RunnerHost> cur = owned.stream().filter(h -> h.getId().equals(currentHostId)).toList();
            if (!cur.isEmpty()) {
                return cur;
            }
        }
        return owned.size() > 12 ? owned.subList(0, 12) : owned;
    }

    private static String text(JsonNode input, String field) {
        if (input == null || !input.hasNonNull(field)) {
            return "";
        }
        return input.get(field).asText("").strip();
    }
}
