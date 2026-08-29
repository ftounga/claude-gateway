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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID tokenId = UUID.randomUUID();

    private RunnerCallDispatcher dispatcher;

    @org.junit.jupiter.api.BeforeEach
    void setUpDispatcher() {
        // Dispatcher réel (grâce raccourcie) : le routage des trames non-heartbeat n'a d'intérêt que
        // s'il aboutit vraiment quelque part.
        dispatcher = new RunnerCallDispatcher(registry, objectMapper, 100L);
    }

    private RunnerWebSocketHandler handler() {
        return new RunnerWebSocketHandler(registry, heartbeatService, objectMapper, dispatcher);
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
        org.assertj.core.api.Assertions.assertThat(registered.workspaceId()).isEqualTo(workspaceId);
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
