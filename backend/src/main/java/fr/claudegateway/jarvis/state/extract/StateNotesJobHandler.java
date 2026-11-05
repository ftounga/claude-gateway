package fr.claudegateway.jarvis.state.extract;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentCallShape;
import fr.claudegateway.agent.AgentContextPolicy;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentReasoning;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnMode;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.fastbrain.JarvisFastBrainService;
import fr.claudegateway.jarvis.state.JarvisStateProperties;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.jarvis.state.JarvisStateSettings;
import fr.claudegateway.jarvis.state.JarvisStateSpend;
import fr.claudegateway.jarvis.state.JarvisStateVocabulary;
import fr.claudegateway.shared.jobs.AsyncJobHandler;
import fr.claudegateway.shared.jobs.AsyncJobQueue;

/**
 * <b>Les constats rédigés en différé</b> (F-247 / SF-247-14, cadrage §4.7) : l'exécutant {@code STATE_NOTES} de la file
 * durable couvre la longue traîne que les extracteurs ne reconnaissent pas. Il relit la réponse du tour <b>sous le
 * {@code user_id} du travail</b>, ne garde que les sorties d'état exploitables <b>non couvertes</b> (≈ 8 000 jetons au
 * plus), fait <b>un</b> appel au modèle rapide par l'abstraction fournisseur, et ne range que les faits dont la
 * citation est vérifiée mot pour mot ({@link StateNotesGuard}) : source {@code NOTE}, confiance {@code MOYENNE},
 * heure de la sortie. Plafond propre 15 €/mois (part {@code ETAT}), 200 travaux par jour.
 */
@Component
public class StateNotesJobHandler implements AsyncJobHandler {

    public static final String KIND = "STATE_NOTES";
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtelierMessageRepository messages;
    private final WorkspaceRepository workspaces;
    private final JarvisStateRegistry registry;
    private final StateExtractJobHandler extract;
    private final JarvisStateProperties properties;
    private final JarvisStateSpend spend;
    private final AiAgentProvider provider;
    private final JarvisFastBrainService fastBrain;
    private final AsyncJobQueue queue;
    private final JdbcTemplate jdbc;
    private final int dailyMax;
    private final AtomicLong rejected = new AtomicLong();

    public StateNotesJobHandler(AtelierMessageRepository messages, WorkspaceRepository workspaces,
            JarvisStateRegistry registry, StateExtractJobHandler extract, JarvisStateProperties properties,
            JarvisStateSpend spend, AiAgentProvider provider, JarvisFastBrainService fastBrain, AsyncJobQueue queue,
            JdbcTemplate jdbc, @Value("${app.jarvis.state.notes-daily-max:200}") int dailyMax) {
        this.messages = messages;
        this.workspaces = workspaces;
        this.registry = registry;
        this.extract = extract;
        this.properties = properties;
        this.spend = spend;
        this.provider = provider;
        this.fastBrain = fastBrain;
        this.queue = queue;
        this.jdbc = jdbc;
        this.dailyMax = dailyMax;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean active() {
        return properties.notes();
    }

    @Override
    public int pendingCap() {
        return 500;
    }

    /** Met un tour aux constats, au plus 200 par jour et par compte. Ne lève jamais. */
    public void enqueue(UUID userId, UUID messageId, UUID workspaceId) {
        try {
            Integer today = jdbc.queryForObject("SELECT count(*) FROM async_jobs WHERE user_id = ? AND kind = ? "
                    + "AND created_at >= ?", Integer.class, userId, KIND,
                    Timestamp.from(LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant()));
            if (today != null && today >= dailyMax) {
                return;
            }
            queue.enqueue(AsyncJobQueue.Request.of(userId, KIND, StateExtractJobHandler.payload(messageId, workspaceId)));
        } catch (RuntimeException ignored) {
            // un constat manqué n'est jamais une panne
        }
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
        if (!s.isEnabled() || !s.isNotesEnabled()) {
            return Outcome.drop("disabled");
        }
        List<StateExtractJobHandler.Uncovered> uncovered = extract.uncovered(userId, workspace.get().getId(),
                message.get());
        List<StateNotesGuard.Output> outputs = outputs(uncovered);
        if (outputs.isEmpty()) {
            return Outcome.done();
        }
        if (!spend.maySpend(userId, JarvisStateSpend.Part.NOTES)) {
            return Outcome.drop("cost_cap");
        }
        AgentTurn turn = provider.nextTurn(new AgentTurnRequest(fastBrain.model(), StateNotesGuard.SYSTEM,
                List.of(AgentMessage.userText(StateNotesGuard.message(outputs))), List.of(),
                fastBrain.apiKeyFor(userId), AgentReasoning.none(), AgentContextPolicy.none(), AgentTurnMode.ACT,
                new AgentCallShape(StateNotesGuard.MAX_OUTPUT_TOKENS, false, false, false)));
        BigDecimal usd = spend.account(userId, workspace.get().getId(), JarvisStateSpend.Part.NOTES, turn,
                fastBrain.model());
        StateNotesGuard.Verdict verdict = StateNotesGuard.check(turn == null ? null : turn.text(), outputs);
        rejected.addAndGet(verdict.rejected());
        List<JarvisStateRegistry.Observation> observations = new ArrayList<>();
        for (StateNotesGuard.Fact f : verdict.kept()) {
            StateExtractJobHandler.Uncovered from = uncovered.get(f.output() - 1);
            observations.add(new JarvisStateRegistry.Observation(hostId, workspace.get().getId(), f.kind(), f.key(),
                    f.attribute(), f.value(), from.observedAt(), JarvisStateVocabulary.SOURCE_NOTE,
                    message.get().getId().toString(), JarvisStateVocabulary.MOYENNE));
        }
        if (!observations.isEmpty()) {
            registry.record(userId, observations);
        }
        // SF-247-17 — une sortie constatée au fil de l'eau n'est jamais relue par le rattrapage de l'historique.
        HistoryNotesReader.mark(jdbc, java.time.Clock.systemUTC(), userId, hostId,
                outputs.stream().map(o -> HistoryNotesSelector.fingerprint(o.body())).toList());
        return Outcome.done(usd);
    }

    /** Les sorties numérotées, bornées à ≈ 8 000 jetons au total (dans l'ordre du tour). */
    static List<StateNotesGuard.Output> outputs(List<StateExtractJobHandler.Uncovered> uncovered) {
        List<StateNotesGuard.Output> out = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < uncovered.size(); i++) {
            String body = uncovered.get(i).body();
            if (body == null || body.isBlank() || body.length() > StateNotesGuard.MAX_PER_OUTPUT
                    || total + body.length() > StateNotesGuard.MAX_INPUT_CHARS) {
                continue;
            }
            total += body.length();
            out.add(new StateNotesGuard.Output(i + 1, uncovered.get(i).command(), body));
        }
        return out;
    }

    /** Les faits rejetés par la garde de citation depuis le démarrage (mesure). */
    public long rejected() {
        return rejected.get();
    }

    private static Optional<UUID> uuid(JsonNode p, String field) {
        try {
            return Optional.of(UUID.fromString(p.path(field).asText()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
