package fr.claudegateway.runner.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.RunnerIdentity;

/**
 * Tests du routage des appels d'outils vers un runner (F-38 / SF-38-05, contrat de messages §§1-8).
 *
 * <p>Ce qui est vérifié ici n'est pas « une trame part » mais les quatre façons dont un appel peut
 * <b>mal</b> finir sans que personne ne s'en rende compte : socket sur l'autre replica, silence du
 * runner, fermeture en vol, et réponse rattachée au mauvais workspace.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunnerCallDispatcherTest {

    @Mock
    private RunnerRegistry registry;
    @Mock
    private WebSocketSession session;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID tokenId = UUID.randomUUID();
    private final RunnerIdentity identity = new RunnerIdentity(tokenId, userId, workspaceId);
    private final Map<String, Object> attributes = new HashMap<>();

    private RunnerCallDispatcher dispatcher;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        // Grâce raccourcie : le contrat impose 5 000 ms en production, inutilisable dans un test.
        dispatcher = new RunnerCallDispatcher(registry, objectMapper, 120L);
        executor = Executors.newSingleThreadExecutor();
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** Runner présent et hébergé par ce nœud. */
    private void withLocalRunner() {
        when(registry.findLocal(workspaceId)).thenReturn(Optional.of(new RunnerConnection(
                workspaceId, userId, tokenId, "node-1", OffsetDateTime.now())));
        dispatcher.attach(session, identity);
    }

    /** Fait répondre le runner (dans le fil de l'émission) par la trame donnée. */
    private void respondWith(String frameJson) throws Exception {
        doAnswer(invocation -> {
            dispatcher.onFrame(identity, "tool_result", objectMapper.readTree(frameJson));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
    }

    private JsonNode sentFrame() throws Exception {
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(sent.capture());
        return objectMapper.readTree(sent.getValue().getPayload());
    }

    @Test
    void emitsAConformingToolCallAndResolvesOnTheResult() throws Exception {
        withLocalRunner();
        respondWith("{\"type\":\"tool_result\",\"id\":\"toolu_1\",\"ok\":true,"
                + "\"content\":\"bonjour\",\"durationMs\":12,\"bytes\":7}");

        RunnerCallResult result = dispatcher.call(workspaceId, "toolu_1", "read_file",
                objectMapper.readTree("{\"path\":\"src/a.ts\"}"), 30_000L);

        JsonNode frame = sentFrame();
        assertThat(frame.path("type").asText()).isEqualTo("tool_call");
        assertThat(frame.path("id").asText()).isEqualTo("toolu_1");
        assertThat(frame.path("tool").asText()).isEqualTo("read_file");
        assertThat(frame.path("input").path("path").asText()).isEqualTo("src/a.ts");
        assertThat(frame.path("timeoutMs").asLong()).isEqualTo(30_000L);
        assertThat(result.ok()).isTrue();
        assertThat(result.content()).isEqualTo("bonjour");
        assertThat(result.durationMs()).isEqualTo(12L);
        assertThat(result.bytes()).isEqualTo(7L);
    }

    @Test
    void relaysARunnerErrorWithItsCode() throws Exception {
        withLocalRunner();
        respondWith("{\"type\":\"tool_result\",\"id\":\"toolu_1\",\"ok\":false,"
                + "\"error\":{\"code\":\"not_found\",\"message\":\"Fichier introuvable : src/a.ts\"},"
                + "\"durationMs\":3}");

        RunnerCallResult result = dispatcher.call(workspaceId, "toolu_1", "read_file",
                objectMapper.readTree("{\"path\":\"src/a.ts\"}"), 30_000L);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("not_found");
        assertThat(result.errorMessage()).isEqualTo("Fichier introuvable : src/a.ts");
    }

    @Test
    void refusesImmediatelyWhenTheSocketLivesOnAnotherReplica() throws Exception {
        // Contrat §8 : isConnected() est vrai cross-replica, findLocal() ne l'est pas. Aucun relais
        // inter-pods n'existe en v1 : il faut échouer tout de suite, pas attendre un silence.
        when(registry.findLocal(workspaceId)).thenReturn(Optional.empty());
        when(registry.isConnected(workspaceId)).thenReturn(true);

        RunnerCallResult result = dispatcher.call(workspaceId, "toolu_1", "list_files",
                objectMapper.createObjectNode(), 30_000L);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE);
        verify(session, never()).sendMessage(any());
    }

    @Test
    void reportsUnavailableWhenNoRunnerIsConnectedAtAll() {
        when(registry.findLocal(workspaceId)).thenReturn(Optional.empty());
        when(registry.isConnected(workspaceId)).thenReturn(false);

        RunnerCallResult result = dispatcher.call(workspaceId, "toolu_1", "list_files",
                objectMapper.createObjectNode(), 30_000L);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.RUNNER_UNAVAILABLE);
    }

    @Test
    void abandonsAndCancelsWhenTheRunnerStaysSilent() throws Exception {
        withLocalRunner();

        RunnerCallResult result = dispatcher.call(workspaceId, "toolu_1", "read_file",
                objectMapper.readTree("{\"path\":\"a.ts\"}"), 10L);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.RUNNER_TIMEOUT);
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.times(2)).sendMessage(sent.capture());
        JsonNode cancel = objectMapper.readTree(sent.getAllValues().get(1).getPayload());
        assertThat(cancel.path("type").asText()).isEqualTo("tool_cancel");
        assertThat(cancel.path("id").asText()).isEqualTo("toolu_1");
        assertThat(cancel.path("reason").asText()).isEqualTo("timeout");
    }

    @Test
    void ignoresAResultThatBelongsToAnotherWorkspace() throws Exception {
        withLocalRunner();
        RunnerIdentity intruder = new RunnerIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        doAnswer(invocation -> {
            // Même id, mais la socket qui répond appartient à un autre workspace : jetée.
            dispatcher.onFrame(intruder, "tool_result", objectMapper.readTree(
                    "{\"type\":\"tool_result\",\"id\":\"toolu_1\",\"ok\":true,\"content\":\"volé\"}"));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        RunnerCallResult result = dispatcher.call(workspaceId, "toolu_1", "read_file",
                objectMapper.readTree("{\"path\":\"a.ts\"}"), 10L);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.RUNNER_TIMEOUT);
        assertThat(result.content()).isEmpty();
    }

    @Test
    void terminatesInFlightCallsWhenTheSocketCloses() throws Exception {
        withLocalRunner();
        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(invocation -> {
            sent.countDown();
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        Future<RunnerCallResult> pending = executor.submit(() -> dispatcher.call(
                workspaceId, "toolu_1", "read_file", objectMapper.readTree("{\"path\":\"a.ts\"}"),
                60_000L));
        assertThat(sent.await(2, TimeUnit.SECONDS)).isTrue();

        dispatcher.detach(session, identity);

        RunnerCallResult result = pending.get(2, TimeUnit.SECONDS);
        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.RUNNER_UNAVAILABLE);
    }

    @Test
    void terminatesTheCallOnAProtocolError() throws Exception {
        withLocalRunner();
        doAnswer(invocation -> {
            dispatcher.onFrame(identity, "protocol_error", objectMapper.readTree(
                    "{\"type\":\"protocol_error\",\"code\":\"invalid_envelope\",\"id\":\"toolu_1\"}"));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        RunnerCallResult result = dispatcher.call(workspaceId, "toolu_1", "read_file",
                objectMapper.readTree("{\"path\":\"a.ts\"}"), 10L);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.RUNNER_PROTOCOL_ERROR);
    }

    @Test
    void treatsANonTextualContentAsANonConformingAnswer() throws Exception {
        withLocalRunner();
        // `content` est une STRING obligatoire quand ok=true : un objet n'est pas « un contenu vide ».
        respondWith("{\"type\":\"tool_result\",\"id\":\"toolu_1\",\"ok\":true,\"content\":{\"a\":1}}");

        RunnerCallResult result = dispatcher.call(workspaceId, "toolu_1", "read_file",
                objectMapper.readTree("{\"path\":\"a.ts\"}"), 10L);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.RUNNER_PROTOCOL_ERROR);
    }

    @Test
    void refusesAToolTheRunnerDidNotAnnounce() throws Exception {
        withLocalRunner();
        dispatcher.onFrame(identity, "ready", objectMapper.readTree(
                "{\"type\":\"ready\",\"protocol\":1,\"capabilities\":[\"files\"]}"));

        RunnerCallResult result = dispatcher.call(workspaceId, "toolu_1", "bash",
                objectMapper.readTree("{\"command\":\"ls\"}"), 120_000L);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.UNSUPPORTED_TOOL);
        verify(session, never()).sendMessage(any());
    }

    @Test
    void ignoresUnknownFrameTypesAndOrphanResults() throws Exception {
        withLocalRunner();

        // Compatibilité ascendante (contrat §0) : ni exception, ni fermeture de socket.
        dispatcher.onFrame(identity, "trame_du_futur", objectMapper.readTree("{\"type\":\"trame_du_futur\"}"));
        dispatcher.onFrame(identity, "tool_result", objectMapper.readTree(
                "{\"type\":\"tool_result\",\"id\":\"inconnu\",\"ok\":true,\"content\":\"x\"}"));
        dispatcher.onFrame(identity, "tool_stream", objectMapper.readTree(
                "{\"type\":\"tool_stream\",\"id\":\"inconnu\",\"seq\":0,\"chunk\":\"x\"}"));

        verify(session, never()).sendMessage(any());
    }

    @Test
    void heartbeatUsesTheSameDecoratedSessionAsToolCalls() {
        withLocalRunner();

        assertThat(dispatcher.outboundFor(session)).isNotSameAs(session);
        dispatcher.detach(session, identity);
        // Après détachement, plus de décorateur : on retombe sur la session brute, jamais sur rien.
        assertThat(dispatcher.outboundFor(session)).isSameAs(session);
    }

    @Test
    void closingAStaleSessionDoesNotEvictTheReconnectedOne() throws Exception {
        withLocalRunner();
        WebSocketSession stale = org.mockito.Mockito.mock(WebSocketSession.class);
        when(stale.getAttributes()).thenReturn(new HashMap<>());

        // Fermeture tardive d'une socket qui n'a jamais été attachée : la connexion vivante reste.
        dispatcher.detach(stale, identity);

        assertThat(dispatcher.outboundFor(session)).isNotSameAs(session);
    }

    // ------------------------------------------------- flux et annulation (SF-38-07)

    /** Runner local ayant annoncé la capacité {@code bash} (contrat §2.1) : sans elle, rien ne part. */
    private void withLocalBashRunner() throws Exception {
        withLocalRunner();
        dispatcher.onFrame(identity, "ready", objectMapper.readTree(
                "{\"type\":\"ready\",\"protocol\":1,\"capabilities\":[\"files\",\"bash\"]}"));
    }

    @Test
    void relaysStreamChunksInOrderThenDetachesOnTheResult() throws Exception {
        withLocalBashRunner();
        java.util.List<String> relayed = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        doAnswer(invocation -> {
            dispatcher.onFrame(identity, "tool_stream", objectMapper.readTree(
                    "{\"type\":\"tool_stream\",\"id\":\"toolu_1\",\"seq\":0,\"stream\":\"stdout\",\"chunk\":\"un\\n\"}"));
            dispatcher.onFrame(identity, "tool_stream", objectMapper.readTree(
                    "{\"type\":\"tool_stream\",\"id\":\"toolu_1\",\"seq\":1,\"stream\":\"stderr\",\"chunk\":\"deux\\n\"}"));
            dispatcher.onFrame(identity, "tool_result", objectMapper.readTree(
                    "{\"type\":\"tool_result\",\"id\":\"toolu_1\",\"ok\":true,\"content\":\"\",\"exitCode\":0}"));
            // Fragment tardif : l'appel est terminé, plus rien ne doit être relayé (contrat §2.3).
            dispatcher.onFrame(identity, "tool_stream", objectMapper.readTree(
                    "{\"type\":\"tool_stream\",\"id\":\"toolu_1\",\"seq\":2,\"stream\":\"stdout\",\"chunk\":\"trop tard\"}"));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        RunnerCallResult result = dispatcher.call(workspaceId, "toolu_1", "bash",
                objectMapper.readTree("{\"command\":\"ls\"}"), 5_000L, relayed::add);

        assertThat(result.ok()).isTrue();
        assertThat(result.exitCode()).isZero();
        // L'agrégat conserve l'entrelacement réel des deux flux, dans l'ordre des seq.
        assertThat(result.streamed()).isEqualTo("un\ndeux\n");
        assertThat(relayed).containsExactly("un\n", "deux\n");
    }

    @Test
    void aFailingRelayNeverBreaksTheCall() throws Exception {
        withLocalBashRunner();
        doAnswer(invocation -> {
            dispatcher.onFrame(identity, "tool_stream", objectMapper.readTree(
                    "{\"type\":\"tool_stream\",\"id\":\"toolu_1\",\"seq\":0,\"stream\":\"stdout\",\"chunk\":\"x\"}"));
            dispatcher.onFrame(identity, "tool_result", objectMapper.readTree(
                    "{\"type\":\"tool_result\",\"id\":\"toolu_1\",\"ok\":true,\"content\":\"\",\"exitCode\":0}"));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        // Le client SSE est parti : le relais lève, la commande continue quand même d'être agrégée.
        RunnerCallResult result = dispatcher.call(workspaceId, "toolu_1", "bash",
                objectMapper.readTree("{\"command\":\"ls\"}"), 5_000L, chunk -> {
                    throw new IllegalStateException("flux client fermé");
                });

        assertThat(result.ok()).isTrue();
        assertThat(result.streamed()).isEqualTo("x");
    }

    @Test
    void cancelWorkspaceSendsAToolCancelForEachInFlightCall() throws Exception {
        withLocalBashRunner();
        CountDownLatch emitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            emitted.countDown();
            return null; // le runner ne répond pas : l'appel reste en vol
        }).when(session).sendMessage(any(TextMessage.class));

        Future<RunnerCallResult> pending = executor.submit(() -> dispatcher.call(workspaceId,
                "toolu_1", "bash", objectMapper.readTree("{\"command\":\"sleep 300\"}"), 5_000L, null));
        assertThat(emitted.await(5, TimeUnit.SECONDS)).isTrue();

        int cancelled = dispatcher.cancelWorkspace(workspaceId, "user_interrupt");

        assertThat(cancelled).isEqualTo(1);
        ArgumentCaptor<TextMessage> frames = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(2)).sendMessage(frames.capture());
        JsonNode cancel = objectMapper.readTree(frames.getAllValues().get(1).getPayload());
        assertThat(cancel.path("type").asText()).isEqualTo("tool_cancel");
        assertThat(cancel.path("id").asText()).isEqualTo("toolu_1");
        assertThat(cancel.path("reason").asText()).isEqualTo("user_interrupt");
        pending.cancel(true);
    }

    @Test
    void cancelWorkspaceIgnoresCallsOfAnotherWorkspace() throws Exception {
        withLocalBashRunner();
        CountDownLatch emitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            emitted.countDown();
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        Future<RunnerCallResult> pending = executor.submit(() -> dispatcher.call(workspaceId,
                "toolu_1", "bash", objectMapper.readTree("{\"command\":\"ls\"}"), 5_000L, null));
        assertThat(emitted.await(5, TimeUnit.SECONDS)).isTrue();

        // Isolation : le workspace d'un autre utilisateur n'annule rien ici.
        assertThat(dispatcher.cancelWorkspace(UUID.randomUUID(), "user_interrupt")).isZero();
        pending.cancel(true);
    }

    // ---------------------------------------------------------- repli long-polling (SF-38-09)

    @Test
    void aCallRoutedToALongPollingChannelIsQueuedInsteadOfWritten() throws Exception {
        // Le dispatcher ne sait pas quel transport porte le runner : le tool_call doit partir de la
        // même façon vers une file de long-polling que vers une socket.
        when(registry.findLocal(workspaceId)).thenReturn(Optional.of(new RunnerConnection(
                workspaceId, userId, tokenId, "node-1", OffsetDateTime.now())));
        LongPollingRunnerOutbound channel =
                new LongPollingRunnerOutbound(workspaceId, userId, tokenId, null);
        dispatcher.attachChannel(identity, channel);

        Future<RunnerCallResult> pending = executor.submit(() -> dispatcher.call(workspaceId,
                "toolu_poll", "read_file", objectMapper.readTree("{\"path\":\"a.txt\"}"), 300L));

        java.util.List<String> frames = channel.drain(java.time.Duration.ofSeconds(5));
        assertThat(frames).hasSize(1);
        JsonNode frame = objectMapper.readTree(frames.get(0));
        assertThat(frame.path("type").asText()).isEqualTo("tool_call");
        assertThat(frame.path("id").asText()).isEqualTo("toolu_poll");
        assertThat(frame.path("tool").asText()).isEqualTo("read_file");
        assertThat(frame.path("input").path("path").asText()).isEqualTo("a.txt");
        // Le résultat arrive par POST /runner/send, donc par le même onFrame que le WebSocket.
        dispatcher.onFrame(identity, "tool_result", objectMapper.readTree(
                "{\"type\":\"tool_result\",\"id\":\"toolu_poll\",\"ok\":true,"
                        + "\"content\":\"contenu\",\"durationMs\":4}"));
        assertThat(pending.get(5, TimeUnit.SECONDS).content()).isEqualTo("contenu");
    }

    @Test
    void closingALongPollingChannelFailsItsInFlightCalls() throws Exception {
        when(registry.findLocal(workspaceId)).thenReturn(Optional.of(new RunnerConnection(
                workspaceId, userId, tokenId, "node-1", OffsetDateTime.now())));
        LongPollingRunnerOutbound channel =
                new LongPollingRunnerOutbound(workspaceId, userId, tokenId,
                        c -> dispatcher.detachChannel(workspaceId, c));
        dispatcher.attachChannel(identity, channel);
        Future<RunnerCallResult> pending = executor.submit(() -> dispatcher.call(workspaceId,
                "toolu_lost", "read_file", objectMapper.readTree("{\"path\":\"a.txt\"}"), 60_000L));
        assertThat(channel.drain(java.time.Duration.ofSeconds(5))).hasSize(1);

        channel.close();

        // Aucun rejeu : un appel perdu avec le canal est une erreur rendue au modèle (contrat §7).
        RunnerCallResult result = pending.get(5, TimeUnit.SECONDS);
        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.RUNNER_UNAVAILABLE);
    }
}
