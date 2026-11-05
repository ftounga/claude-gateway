package fr.claudegateway.jarvis.state.extract;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.state.JarvisStateProperties;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.jarvis.state.JarvisStateSettings;
import fr.claudegateway.jarvis.state.JarvisStateVocabulary;
import fr.claudegateway.shared.jobs.AsyncJobHandler;
import fr.claudegateway.shared.jobs.AsyncJobQueue;

/**
 * <b>L'extraction après un tour</b> (F-247 / SF-247-04, cadrage §4.2) : l'exécutant {@code STATE_EXTRACT} de la file
 * durable. Il relit la transcription du tour <b>sous le {@code user_id} du travail</b>, applique les extracteurs purs
 * aux sorties {@code bash} réussies et entières, et range les faits au registre (source {@code TOUR}). Aucun appel
 * modèle ; quelques millisecondes de CPU, hors du chemin de la réponse.
 *
 * <p>La couverture (appels {@code bash} d'état vus, couverts, faits) est comptée par jour et par forme de commande dans
 * {@code jarvis_state_coverage} — sans commande complète ni sortie.</p>
 */
@Component
public class StateExtractJobHandler implements AsyncJobHandler {

    public static final String KIND = "STATE_EXTRACT";
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtelierMessageRepository messages;
    private final WorkspaceRepository workspaces;
    private final JarvisStateRegistry registry;
    private final StateExtractors extractors;
    private final JarvisStateProperties properties;
    private final JdbcTemplate jdbc;

    public StateExtractJobHandler(AtelierMessageRepository messages, WorkspaceRepository workspaces,
            JarvisStateRegistry registry, StateExtractors extractors, JarvisStateProperties properties,
            JdbcTemplate jdbc) {
        this.messages = messages;
        this.workspaces = workspaces;
        this.registry = registry;
        this.extractors = extractors;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean active() {
        return properties.extract();
    }

    /** La charge utile : la réponse du tour et son sujet (références seulement). */
    public static String payload(UUID messageId, UUID workspaceId) {
        return "{\"messageId\":\"" + messageId + "\",\"workspaceId\":\"" + workspaceId + "\"}";
    }

    /** Ce qu'une transcription a donné. */
    public record Summary(int stateCalls, int covered, int facts, List<Uncovered> uncovered) {
    }

    /** Une sortie d'outil d'état exploitable qu'aucun extracteur n'a couverte (pour les constats, SF-247-14). */
    public record Uncovered(String toolUseId, String command, String body, OffsetDateTime observedAt) {
    }

    @Override
    public Outcome handle(AsyncJobQueue.Job job) {
        UUID userId = job.userId();
        JsonNode p;
        try {
            p = MAPPER.readTree(job.payload());
        } catch (Exception e) {
            return Outcome.drop("payload");
        }
        Optional<AtelierMessage> message = uuid(p, "messageId").flatMap(id -> messages.findByIdAndUserId(id, userId));
        Optional<Workspace> workspace = uuid(p, "workspaceId").flatMap(id -> workspaces.findByIdAndUserId(id, userId));
        if (message.isEmpty() || workspace.isEmpty() || workspace.get().getHostId() == null) {
            return Outcome.drop("no_client");
        }
        UUID hostId = workspace.get().getHostId();
        JarvisStateSettings s = registry.settingsOf(userId, hostId);
        if (!s.isEnabled() || !s.isExtractFromTurns()) {
            return Outcome.drop("disabled");
        }
        extract(userId, hostId, workspace.get().getId(), message.get());
        return Outcome.done();
    }

    /** Extrait et range les faits d'une réponse ; rend le bilan (visible des tests et des constats). */
    public Summary extract(UUID userId, UUID hostId, UUID workspaceId, AtelierMessage message) {
        List<JsonNode> blocks = blocks(message.getTerminalJson());
        OffsetDateTime fallback = message.getCreatedAt() == null ? OffsetDateTime.now() : message.getCreatedAt();
        Map<String, OffsetDateTime> at = auditTimes(userId, workspaceId, fallback);
        List<JarvisStateRegistry.Observation> observations = new ArrayList<>();
        Map<String, int[]> perForm = new LinkedHashMap<>();
        List<Uncovered> uncovered = new ArrayList<>();
        int stateCalls = 0;
        int covered = 0;
        for (JsonNode b : blocks) {
            if (!"bash".equals(text(b, "tool")) || b.path("error").asBoolean(false)) {
                continue;
            }
            StateExtractors.Result r = extractors.extract(text(b, "command"), text(b, "output"));
            if (!r.stateCommand()) {
                continue;
            }
            stateCalls++;
            int[] c = perForm.computeIfAbsent(r.form(), k -> new int[3]);
            c[0]++;
            String toolUseId = text(b, "toolUseId");
            OffsetDateTime observed = toolUseId == null ? fallback : at.getOrDefault(toolUseId, fallback);
            if (!r.facts().isEmpty()) {
                covered++;
                c[1]++;
                c[2] += r.facts().size();
                for (StateExtractor.Extracted e : r.facts()) {
                    observations.add(new JarvisStateRegistry.Observation(hostId, workspaceId, e.kind(), e.key(),
                            e.attribute(), e.value(), observed, JarvisStateVocabulary.SOURCE_TOUR,
                            message.getId().toString(), e.confidence()));
                }
            } else if (r.usable()) {
                BashOutput o = BashOutput.parse(text(b, "command"), text(b, "output"));
                uncovered.add(new Uncovered(toolUseId, o.command(), o.body(), observed));
            }
        }
        int facts = observations.isEmpty() ? 0 : registry.record(userId, observations);
        LocalDay day = new LocalDay(fallback.atZoneSameInstant(ZONE).toLocalDate());
        perForm.forEach((form, c) -> count(userId, day, form, c[0], c[1], c[2]));
        return new Summary(stateCalls, covered, facts, List.copyOf(uncovered));
    }

    private record LocalDay(java.time.LocalDate date) {
    }

    /** L'heure de chaque appel d'outil du tour, lue au journal du runner (index {@code user_id, workspace_id, created_at}). */
    private Map<String, OffsetDateTime> auditTimes(UUID userId, UUID workspaceId, OffsetDateTime end) {
        Map<String, OffsetDateTime> out = new HashMap<>();
        try {
            jdbc.query("SELECT call_id, created_at FROM runner_audit WHERE user_id = ? AND workspace_id = ? "
                    + "AND created_at BETWEEN ? AND ?", rs -> {
                        Timestamp t = rs.getTimestamp(2);
                        if (t != null) {
                            out.put(rs.getString(1), t.toInstant().atOffset(ZoneOffset.UTC));
                        }
                    }, userId, workspaceId, Timestamp.from(end.minusHours(6).toInstant()),
                    Timestamp.from(end.plusMinutes(1).toInstant()));
        } catch (RuntimeException ignored) {
            // repli : l'heure de la réponse
        }
        return out;
    }

    /** Ajoute au compteur de couverture (une ligne par compte, jour, forme). */
    void count(UUID userId, LocalDay day, String form, int calls, int covered, int facts) {
        String f = form.length() > 80 ? form.substring(0, 80) : form;
        Date d = Date.valueOf(day.date());
        int n = jdbc.update("UPDATE jarvis_state_coverage SET calls = calls + ?, covered = covered + ?, "
                + "facts = facts + ? WHERE user_id = ? AND obs_day = ? AND form = ?", calls, covered, facts, userId, d, f);
        if (n == 0) {
            try {
                jdbc.update("INSERT INTO jarvis_state_coverage (user_id, obs_day, form, calls, covered, facts) "
                        + "VALUES (?,?,?,?,?,?)", userId, d, f, calls, covered, facts);
            } catch (DuplicateKeyException race) {
                jdbc.update("UPDATE jarvis_state_coverage SET calls = calls + ?, covered = covered + ?, "
                        + "facts = facts + ? WHERE user_id = ? AND obs_day = ? AND form = ?", calls, covered, facts,
                        userId, d, f);
            }
        }
    }

    static List<JsonNode> blocks(String terminalJson) {
        List<JsonNode> out = new ArrayList<>();
        if (terminalJson == null || terminalJson.isBlank()) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(terminalJson);
            JsonNode blocks = root.path("blocks");
            if (blocks.isArray()) {
                blocks.forEach(out::add);
            }
        } catch (Exception ignored) {
            // relevé illisible : rien
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Optional<UUID> uuid(JsonNode p, String field) {
        try {
            return Optional.of(UUID.fromString(p.path(field).asText()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
