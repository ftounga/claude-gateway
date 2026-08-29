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
 * Gestionnaire du canal WebSocket runner (F-38 / SF-38-02). À l'établissement il enregistre la
 * connexion dans le {@link RunnerRegistry} et marque le runner vu ({@code last_seen_at}). Chaque
 * heartbeat ({@code {"type":"heartbeat"}} ou trame pong) rafraîchit {@code last_seen_at} et reçoit un
 * {@code heartbeat_ack}. À la fermeture, la connexion est retirée du registre.
 *
 * <p>SF-38-02 ne transporte <b>pas</b> d'exécution d'outil : les messages autres que le heartbeat
 * sont ignorés (le routage des outils arrive en SF-38-05).</p>
 */
@Component
public class RunnerWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RunnerWebSocketHandler.class);

    private final RunnerRegistry registry;
    private final RunnerHeartbeatService heartbeatService;
    private final ObjectMapper objectMapper;
    private final String nodeId = UUID.randomUUID().toString();

    public RunnerWebSocketHandler(RunnerRegistry registry, RunnerHeartbeatService heartbeatService,
            ObjectMapper objectMapper) {
        this.registry = registry;
        this.heartbeatService = heartbeatService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        RunnerIdentity identity = identityOf(session);
        registry.register(new RunnerConnection(
                identity.workspaceId(), identity.userId(), identity.tokenId(), nodeId,
                OffsetDateTime.now()));
        heartbeatService.touch(identity.tokenId());
        log.debug("Runner connecte: workspace={} token={}", identity.workspaceId(), identity.tokenId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        RunnerIdentity identity = identityOf(session);
        String type = parseType(message.getPayload());
        if ("heartbeat".equals(type)) {
            heartbeatService.touch(identity.tokenId());
            session.sendMessage(new TextMessage("{\"type\":\"heartbeat_ack\"}"));
        }
        // Tout autre type est ignoré en SF-38-02 (pas encore d'exécution d'outil).
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        heartbeatService.touch(identityOf(session).tokenId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        RunnerIdentity identity = identityOf(session);
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

    private String parseType(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            return node.path("type").asText(null);
        } catch (Exception e) {
            return null; // Charge utile illisible : ignorée.
        }
    }
}
