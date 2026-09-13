package fr.claudegateway.runner.channel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.RunnerHeartbeatService;
import fr.claudegateway.runner.RunnerIdentity;
import fr.claudegateway.runner.RunnerLiveness;

/**
 * Tests du gestionnaire WebSocket runner (F-38 / SF-38-02) : enregistrement à l'établissement,
 * heartbeat (accusé + rafraîchissement de last_seen_at), et retrait à la fermeture.
 */
@ExtendWith(MockitoExtension.class)
class RunnerWebSocketHandlerTest {

    @Mock
    private RunnerRegistry registry;
    @Mock
    private RunnerHeartbeatService heartbeatService;
    @Mock
    private WebSocketSession session;
    @Mock
    private RunnerLiveness liveness;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID tokenId = UUID.randomUUID();

    private RunnerCallDispatcher dispatcher;

    @org.junit.jupiter.api.BeforeEach
    void setUpDispatcher() {
        // Dispatcher réel (grâce raccourcie) : le routage des trames non-heartbeat n'a d'intérêt que
        // s'il aboutit vraiment quelque part.
        dispatcher = new RunnerCallDispatcher(registry, objectMapper, (id, shell) -> { },
                (id, version) -> { }, new fr.claudegateway.runner.ServedRunnerVersion("", ""),
                liveness, 100L);
    }

    private RunnerWebSocketHandler handler() {
        return new RunnerWebSocketHandler(registry, heartbeatService, objectMapper, dispatcher,
                liveness);
    }

    private void withIdentity() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(RunnerHandshakeInterceptor.IDENTITY_ATTRIBUTE,
                new RunnerIdentity(tokenId, userId, workspaceId));
        when(session.getAttributes()).thenReturn(attributes);
    }

    @Test
    void establishingConnectionRegistersAndTouches() {
        withIdentity();

        handler().afterConnectionEstablished(session);

        ArgumentCaptor<RunnerConnection> captor = ArgumentCaptor.forClass(RunnerConnection.class);
        verify(registry).register(captor.capture());
        RunnerConnection registered = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(registered.hostId()).isEqualTo(workspaceId);
        org.assertj.core.api.Assertions.assertThat(registered.tokenId()).isEqualTo(tokenId);
        verify(heartbeatService).touch(tokenId);
    }

    @Test
    void heartbeatMessageAcksAndTouches() throws Exception {
        withIdentity();

        handler().handleTextMessage(session, new TextMessage("{\"type\":\"heartbeat\"}"));

        verify(heartbeatService).touch(tokenId);
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(sent.capture());
        org.assertj.core.api.Assertions.assertThat(sent.getValue().getPayload()).contains("heartbeat_ack");
    }

    @Test
    void nonHeartbeatMessageIsIgnored() throws Exception {
        withIdentity();

        handler().handleTextMessage(session, new TextMessage("{\"type\":\"autre\"}"));

        verify(heartbeatService, never()).touch(any());
        verify(session, never()).sendMessage(any());
    }

    @Test
    void closingConnectionUnregisters() {
        withIdentity();

        handler().afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(registry).unregister(workspaceId, tokenId);
    }

    // ------------------------------------- balayage des sockets muettes (F-97 / SF-97-01)

    /** Socket de test : attributs vivants et fermeture observable. */
    private static final class FakeSession {
        private final WebSocketSession ws = org.mockito.Mockito.mock(WebSocketSession.class,
                org.mockito.Mockito.withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        private volatile CloseStatus closedWith;

        FakeSession(RunnerIdentity identity) {
            Map<String, Object> attributes = new HashMap<>();
            attributes.put(RunnerHandshakeInterceptor.IDENTITY_ATTRIBUTE, identity);
            when(ws.getAttributes()).thenReturn(attributes);
            when(ws.isOpen()).thenAnswer(invocation -> closedWith == null);
            try {
                org.mockito.Mockito.doAnswer(invocation -> {
                    closedWith = invocation.getArgument(0);
                    return null;
                }).when(ws).close(any(CloseStatus.class));
            } catch (java.io.IOException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    @Test
    void sweepClosesASilentSocketAndEmptiesTheRegistry() throws Exception {
        // Le cas du PO : Wi-Fi coupé, la socket reste à moitié ouverte, le battement s'arrête.
        InMemoryRunnerRegistry realRegistry = new InMemoryRunnerRegistry();
        RunnerCallDispatcher realDispatcher = new RunnerCallDispatcher(realRegistry, objectMapper,
                (id, shell) -> { }, (id, version) -> { },
                new fr.claudegateway.runner.ServedRunnerVersion("", ""), liveness, 100L);
        RunnerWebSocketHandler handler = new RunnerWebSocketHandler(realRegistry, heartbeatService,
                objectMapper, realDispatcher, liveness);
        RunnerIdentity identity = new RunnerIdentity(tokenId, userId, workspaceId);
        FakeSession socket = new FakeSession(identity);
        handler.afterConnectionEstablished(socket.ws);
        org.assertj.core.api.Assertions.assertThat(realRegistry.isConnected(workspaceId)).isTrue();

        when(liveness.isAlive(userId, workspaceId)).thenReturn(false);
        handler.sweepSilentSockets();

        org.assertj.core.api.Assertions.assertThat(socket.closedWith)
                .isEqualTo(RunnerWebSocketHandler.SILENT_SOCKET);
        org.assertj.core.api.Assertions.assertThat(socket.closedWith.getCode())
                .isEqualTo(CloseStatus.SESSION_NOT_RELIABLE.getCode());
        org.assertj.core.api.Assertions.assertThat(realRegistry.isConnected(workspaceId)).isFalse();
        // Plus rien n'achemine vers cette socket : un appel est refusé, jamais mis en attente.
        org.assertj.core.api.Assertions.assertThat(realDispatcher.call(
                new RunnerTarget(workspaceId, UUID.randomUUID(), ""), "toolu_1", "list_files",
                objectMapper.createObjectNode(), 30_000L).errorCode())
                .isEqualTo(RunnerErrorCodes.RUNNER_UNAVAILABLE);
    }

    @Test
    void sweepLeavesABeatingSocketAlone() {
        InMemoryRunnerRegistry realRegistry = new InMemoryRunnerRegistry();
        RunnerWebSocketHandler handler = new RunnerWebSocketHandler(realRegistry, heartbeatService,
                objectMapper, dispatcher, liveness);
        FakeSession socket = new FakeSession(new RunnerIdentity(tokenId, userId, workspaceId));
        handler.afterConnectionEstablished(socket.ws);

        when(liveness.isAlive(userId, workspaceId)).thenReturn(true);
        handler.sweepSilentSockets();

        org.assertj.core.api.Assertions.assertThat(socket.closedWith).isNull();
        org.assertj.core.api.Assertions.assertThat(realRegistry.isConnected(workspaceId)).isTrue();
    }

    @Test
    void sweepNeverClosesOnAnUnreadableHeartbeat() {
        // Le doute ne coupe pas : base momentanément illisible, la socket reste ouverte.
        InMemoryRunnerRegistry realRegistry = new InMemoryRunnerRegistry();
        RunnerWebSocketHandler handler = new RunnerWebSocketHandler(realRegistry, heartbeatService,
                objectMapper, dispatcher, liveness);
        FakeSession socket = new FakeSession(new RunnerIdentity(tokenId, userId, workspaceId));
        handler.afterConnectionEstablished(socket.ws);

        when(liveness.isAlive(userId, workspaceId)).thenThrow(new IllegalStateException("base"));
        handler.sweepSilentSockets();

        org.assertj.core.api.Assertions.assertThat(socket.closedWith).isNull();
        org.assertj.core.api.Assertions.assertThat(realRegistry.isConnected(workspaceId)).isTrue();
    }

    @Test
    void sweepOfAnOldSocketNeverErasesAReconnectionUnderANewToken() {
        InMemoryRunnerRegistry realRegistry = new InMemoryRunnerRegistry();
        RunnerWebSocketHandler handler = new RunnerWebSocketHandler(realRegistry, heartbeatService,
                objectMapper, dispatcher, liveness);
        FakeSession old = new FakeSession(new RunnerIdentity(tokenId, userId, workspaceId));
        handler.afterConnectionEstablished(old.ws);
        // Le balayage a déjà jugé le poste muet… quand le runner revient sous un nouveau jeton.
        when(liveness.isAlive(userId, workspaceId)).thenReturn(false);
        UUID newToken = UUID.randomUUID();
        RunnerConnection reconnected = new RunnerConnection(workspaceId, userId, newToken, "node",
                java.time.OffsetDateTime.now());
        realRegistry.register(reconnected);

        handler.sweepSilentSockets();

        org.assertj.core.api.Assertions.assertThat(old.closedWith).isNotNull();
        org.assertj.core.api.Assertions.assertThat(realRegistry.findLocal(workspaceId))
                .contains(reconnected);
    }

    @Test
    void lateCloseOfAnOldSocketNeverErasesAReconnectionUnderTheSameToken() {
        // Un runner qui se reconnecte garde son jeton : la garde par jeton seule ne suffisait pas.
        InMemoryRunnerRegistry realRegistry = new InMemoryRunnerRegistry();
        RunnerWebSocketHandler handler = new RunnerWebSocketHandler(realRegistry, heartbeatService,
                objectMapper, dispatcher, liveness);
        FakeSession old = new FakeSession(new RunnerIdentity(tokenId, userId, workspaceId));
        handler.afterConnectionEstablished(old.ws);
        FakeSession fresh = new FakeSession(new RunnerIdentity(tokenId, userId, workspaceId));
        handler.afterConnectionEstablished(fresh.ws);
        RunnerConnection current = realRegistry.findLocal(workspaceId).orElseThrow();

        handler.afterConnectionClosed(old.ws, CloseStatus.SESSION_NOT_RELIABLE);

        org.assertj.core.api.Assertions.assertThat(realRegistry.findLocal(workspaceId)).contains(current);
    }

    @Test
    void unreadablePayloadIsIgnoredWithoutClosingTheSocket() throws Exception {
        withIdentity();

        handler().handleTextMessage(session, new TextMessage("ceci n'est pas du JSON"));

        verify(heartbeatService, never()).touch(any());
        verify(session, never()).sendMessage(any());
    }

    @Test
    void toolResultIsRoutedToTheDispatcher() throws Exception {
        // SF-38-05 : le handler n'interprète plus les trames d'outil, il les aiguille — avec l'identité
        // de la session, jamais un identifiant lu dans le message.
        withIdentity();

        handler().handleTextMessage(session, new TextMessage(
                "{\"type\":\"tool_result\",\"id\":\"inconnu\",\"ok\":true,\"content\":\"x\"}"));

        // Aucun appel en vol : la trame est jetée en silence, sans erreur ni émission.
        verify(session, never()).sendMessage(any());
    }
}
