package fr.claudegateway.runner.channel;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.RunnerHeartbeatService;
import fr.claudegateway.runner.RunnerIdentity;

/**
 * Gestionnaire du canal WebSocket runner (F-38 / SF-38-02, étendu en SF-38-05). À l'établissement il
 * enregistre la connexion dans le {@link RunnerRegistry} et marque le runner vu
 * ({@code last_seen_at}). Chaque heartbeat ({@code {"type":"heartbeat"}} ou trame pong) rafraîchit
 * {@code last_seen_at} et reçoit un {@code heartbeat_ack}. À la fermeture, la connexion est retirée
 * du registre.
 *
 * <p>Depuis SF-38-05, toute trame <b>autre</b> que le heartbeat est aiguillée vers le
 * {@link RunnerCallDispatcher} avec l'identité issue de la <b>session</b> (jamais un identifiant lu
 * dans le message). Un type inconnu reste ignoré en silence : c'est ce qui permet à un runner
 * antérieur de cohabiter avec un backend plus récent (contrat de messages §0).</p>
 */
@Component
public class RunnerWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RunnerWebSocketHandler.class);

    private final RunnerRegistry registry;
    private final RunnerHeartbeatService heartbeatService;
    private final ObjectMapper objectMapper;
    private final RunnerCallDispatcher dispatcher;
    private final String nodeId = UUID.randomUUID().toString();

    public RunnerWebSocketHandler(RunnerRegistry registry, RunnerHeartbeatService heartbeatService,
            ObjectMapper objectMapper, RunnerCallDispatcher dispatcher) {
        this.registry = registry;
        this.heartbeatService = heartbeatService;
        this.objectMapper = objectMapper;
        this.dispatcher = dispatcher;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        RunnerIdentity identity = identityOf(session);
        // La session décorée est posée AVANT l'enregistrement : dès que la présence est visible, une
        // socket utilisable l'est aussi.
        dispatcher.attach(session, identity);
        registry.register(new RunnerConnection(
                identity.workspaceId(), identity.userId(), identity.tokenId(), nodeId,
                OffsetDateTime.now()));
        heartbeatService.touch(identity.tokenId());
        log.debug("Runner connecte: workspace={} token={}", identity.workspaceId(), identity.tokenId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        RunnerIdentity identity = identityOf(session);
        JsonNode frame = parse(message.getPayload());
        if (frame == null) {
            log.debug("Trame runner illisible ignoree (workspace={})", identity.workspaceId());
            return;
        }
        String type = frame.path("type").asText(null);
        if ("heartbeat".equals(type)) {
            heartbeatService.touch(identity.tokenId());
            // Même instance d'écriture que les tool_call : la session Spring n'est pas thread-safe.
            dispatcher.outboundFor(session).sendMessage(new TextMessage("{\"type\":\"heartbeat_ack\"}"));
            return;
        }
        dispatcher.onFrame(identity, type, frame);
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        heartbeatService.touch(identityOf(session).tokenId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        RunnerIdentity identity = identityOf(session);
        // Les appels en vol sont terminés AVANT le retrait du registre : aucun appel n'attend une
        // socket morte, et aucun n'est rejoué (un write_file rejoué serait destructeur).
        dispatcher.detach(session, identity);
        registry.unregister(identity.workspaceId(), identity.tokenId());
        log.debug("Runner deconnecte: workspace={} token={} ({})",
                identity.workspaceId(), identity.tokenId(), status);
    }

    private RunnerIdentity identityOf(WebSocketSession session) {
        RunnerIdentity identity =
                (RunnerIdentity) session.getAttributes().get(RunnerHandshakeInterceptor.IDENTITY_ATTRIBUTE);
        if (identity == null) {
            // Ne devrait jamais arriver : le handshake interceptor rejette toute session sans jeton.
            throw new IllegalStateException("Session runner sans identite : handshake non authentifie");
        }
        return identity;
    }

    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            return null; // Charge utile illisible : ignorée, la socket n'est jamais fermée pour ça.
        }
    }
}
