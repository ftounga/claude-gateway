package fr.claudegateway.jarvis.state.capture;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.agent.AgentCallShape;
import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentContextPolicy;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentReasoning;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnMode;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.actions.Interlocuteur;
import fr.claudegateway.jarvis.fastbrain.JarvisFastBrainService;
import fr.claudegateway.jarvis.state.JarvisStateProperties;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.jarvis.state.JarvisStateSettings;
import fr.claudegateway.jarvis.state.JarvisStateSpend;
import fr.claudegateway.shared.jobs.AsyncJobHandler;
import fr.claudegateway.shared.jobs.AsyncJobQueue;

/**
 * <b>La lecture d'une capture</b> (F-247 / SF-247-12, cadrage §4.6) : l'exécutant {@code CAPTURE_READ} de la file
 * durable. <b>Un seul appel</b> au modèle rapide, par l'abstraction fournisseur ({@link AiAgentProvider} — jamais
 * Anthropic en direct), avec le bloc {@code image} et la consigne fixe {@link CaptureReadPrompt}. La sortie passe la
 * garde {@link CaptureReadParser} ; la passerelle fixe elle-même le client, le sujet, la date de chaque message et la
 * personne. L'image n'est jamais gardée : la file l'efface dès la fin du travail, réussi ou abandonné.
 */
@Component
public class CaptureReadJobHandler implements AsyncJobHandler {

    public static final String KIND = "CAPTURE_READ";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorkspaceRepository workspaces;
    private final JarvisStateRegistry registry;
    private final JarvisStateProperties properties;
    private final JarvisCaptureStore store;
    private final CaptureSpeakerResolver speakers;
    private final JarvisStateSpend spend;
    private final AiAgentProvider provider;
    private final JarvisFastBrainService fastBrain;
    private final int dailyMax;

    public CaptureReadJobHandler(WorkspaceRepository workspaces, JarvisStateRegistry registry,
            JarvisStateProperties properties, JarvisCaptureStore store, CaptureSpeakerResolver speakers,
            JarvisStateSpend spend, AiAgentProvider provider, JarvisFastBrainService fastBrain,
            @Value("${app.jarvis.state.capture-daily-max:60}") int dailyMax) {
        this.workspaces = workspaces;
        this.registry = registry;
        this.properties = properties;
        this.store = store;
        this.speakers = speakers;
        this.spend = spend;
        this.provider = provider;
        this.fastBrain = fastBrain;
        this.dailyMax = dailyMax;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean active() {
        return properties.captures();
    }

    @Override
    public int pendingCap() {
        return 200;
    }

    /** La charge utile : des références seulement (l'image voyage dans {@code payload_bytes}). */
    public static String payload(UUID depositId, UUID workspaceId, UUID hostId, Instant depositedAt, String mediaType) {
        return payload(depositId, workspaceId, hostId, depositedAt, mediaType, null);
    }

    /**
     * F-247 / SF-247-16 — la même charge utile, marquée de la synchronisation qui l'a remise ({@code syncId}) : sa
     * progression, sa dépense et son annulation se retrouvent par cette marque. L'exécutant l'ignore.
     */
    public static String payload(UUID depositId, UUID workspaceId, UUID hostId, Instant depositedAt, String mediaType,
            UUID syncId) {
        ObjectNode n = MAPPER.createObjectNode();
        if (syncId != null) {
            n.put("syncId", syncId.toString());
        }
        n.put("depositId", depositId.toString());
        n.put("workspaceId", workspaceId.toString());
        n.put("hostId", hostId.toString());
        n.put("depositedAt", depositedAt.toString());
        n.put("mediaType", mediaType);
        return n.toString();
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
        Optional<UUID> depositId = uuid(p, "depositId");
        Optional<Workspace> workspace = uuid(p, "workspaceId").flatMap(id -> workspaces.findByIdAndUserId(id, userId));
        if (depositId.isEmpty() || workspace.isEmpty() || workspace.get().getHostId() == null) {
            return Outcome.drop("no_client");
        }
        if (job.payloadBytes() == null || job.payloadBytes().length == 0) {
            return Outcome.drop("no_image");
        }
        UUID hostId = workspace.get().getHostId();
        JarvisStateSettings s = registry.settingsOf(userId, hostId);
        if (!s.isEnabled() || !s.isCapturesEnabled()) {
            return Outcome.drop("disabled");
        }
        if (store.alreadyRead(userId, depositId.get())) {
            return Outcome.done();
        }
        OffsetDateTime depositedAt = instant(p.path("depositedAt").asText()).atOffset(ZoneOffset.UTC);
        Instant dayStart = LocalDate.ofInstant(Instant.now(), CaptureTimeResolver.ZONE)
                .atStartOfDay(CaptureTimeResolver.ZONE).toInstant();
        if (store.readsSince(userId, dayStart) >= dailyMax) {
            return Outcome.drop("daily_cap");
        }
        if (!spend.maySpend(userId, JarvisStateSpend.Part.CAPTURE)) {
            return Outcome.drop("cost_cap");
        }
        String mediaType = p.path("mediaType").asText("image/png");
        AgentTurn turn = provider.nextTurn(new AgentTurnRequest(fastBrain.model(), CaptureReadPrompt.SYSTEM,
                List.of(new AgentMessage("user", List.of(new AgentContentBlock.Image(mediaType,
                        Base64.getEncoder().encodeToString(job.payloadBytes())),
                        new AgentContentBlock.Text(CaptureReadPrompt.USER)))),
                List.of(), fastBrain.apiKeyFor(userId), AgentReasoning.none(), AgentContextPolicy.none(),
                AgentTurnMode.ACT, new AgentCallShape(CaptureReadPrompt.MAX_OUTPUT_TOKENS, false, false, false)));
        BigDecimal usd = spend.account(userId, workspace.get().getId(), JarvisStateSpend.Part.CAPTURE, turn,
                fastBrain.model());
        CaptureReadParser.Reading reading = CaptureReadParser.parse(turn == null ? null : turn.text());
        store.save(userId, hostId, workspace.get().getId(), depositId.get(), reading.platform(), reading.title(),
                depositedAt, reading.status().name(), usd, lines(userId, hostId, reading, depositedAt));
        return Outcome.done(usd);
    }

    /** La passerelle date chaque message et rapproche chaque personne (jamais le modèle). */
    List<JarvisCaptureStore.NewLine> lines(UUID userId, UUID hostId, CaptureReadParser.Reading reading,
            OffsetDateTime depositedAt) {
        if (reading.status() != CaptureReadParser.Status.LUE) {
            return List.of();
        }
        List<Interlocuteur> known = speakers.known(userId, hostId);
        List<JarvisCaptureStore.NewLine> out = new ArrayList<>();
        CaptureTimeResolver.Resolved last = null;
        for (CaptureReadParser.Line l : reading.lines()) {
            CaptureTimeResolver.Resolved at = CaptureTimeResolver.resolve(l.stamp(), depositedAt);
            // Une bulle sans heure suit la précédente du même bloc : « vers » l'heure de celle-ci, pas celle du dépôt.
            if (l.stamp().isEmpty() && last != null) {
                at = new CaptureTimeResolver.Resolved(last.at(), true);
            }
            last = at;
            CaptureSpeakerResolver.Speaker who = CaptureSpeakerResolver.resolve(l.speaker(), known);
            out.add(new JarvisCaptureStore.NewLine(who.name(), who.known(), at.at(), at.approx(), l.text()));
        }
        return out;
    }

    private static Instant instant(String raw) {
        try {
            return Instant.parse(raw);
        } catch (RuntimeException e) {
            return Instant.now();
        }
    }

    private static Optional<UUID> uuid(JsonNode p, String field) {
        try {
            return Optional.of(UUID.fromString(p.path(field).asText()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
