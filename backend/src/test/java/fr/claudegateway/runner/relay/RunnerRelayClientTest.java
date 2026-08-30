package fr.claudegateway.runner.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import fr.claudegateway.runner.channel.RemoteRunnerNode;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;

/**
 * Tests du client de relais (F-38 / SF-38-12) face à un pair réel — un {@code HttpServer} de la JDK,
 * qui permet de reproduire ce qu'on ne peut pas simuler avec un mock : un corps NDJSON servi
 * <b>ligne à ligne</b>, un 401 de rotation de secret, un flux coupé net.
 *
 * <p>Ce que ces tests protègent : le fait que le {@code streamed} du modèle vienne <b>de la ligne
 * {@code result}</b> et non d'une ré-agrégation locale (sinon la sortie serait comptée deux fois), et
 * le fait que toute panne dégrade vers une erreur qui existait déjà.</p>
 */
class RunnerRelayClientTest {

    private static final String SECRET = "secret-de-relais-de-test-32-octets!!";

    private HttpServer server;
    private RunnerRelayClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> presentedSecret = new AtomicReference<>();
    private final AtomicReference<String> presentedOrigin = new AtomicReference<>();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        RunnerRelayProperties properties = new RunnerRelayProperties();
        properties.setSecret(SECRET);
        properties.setConnectTimeoutMs(2_000L);
        properties.setReadTimeoutMs(5_000L);
        client = new RunnerRelayClient(properties, new RelayPeerClient(properties, objectMapper),
                objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Démarre le pair avec un comportement donné et rend son adresse de base. */
    private RemoteRunnerNode peer(PeerBehaviour behaviour) {
        server.createContext("/api/internal/runner/call", exchange -> {
            presentedSecret.set(exchange.getRequestHeaders().getFirst(RunnerRelayAuthFilter.SECRET_HEADER));
            presentedOrigin.set(exchange.getRequestHeaders().getFirst(RunnerRelayAuthFilter.ORIGIN_HEADER));
            exchange.getRequestBody().readAllBytes();
            behaviour.serve(exchange);
            exchange.close();
        });
        server.start();
        return new RemoteRunnerNode("node-pair",
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void line(OutputStream out, String json) throws IOException {
        out.write(json.getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        out.flush();
    }

    private RunnerCallResult call(RemoteRunnerNode node, java.util.function.Consumer<String> onChunk) {
        return client.call(node, workspaceId, "toolu_1", "bash", objectMapper.createObjectNode(),
                30_000L, onChunk);
    }

    @Test
    void streamLinesAreRelayedInOrderAndResultCarriesTheAggregate() {
        RemoteRunnerNode node = peer(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                line(out, "{\"type\":\"stream\",\"chunk\":\"un\"}");
                line(out, "{\"type\":\"stream\",\"chunk\":\"deux\"}");
                line(out, "{\"type\":\"result\",\"ok\":true,\"content\":\"fini\",\"truncated\":false,"
                        + "\"exitCode\":0,\"durationMs\":42,\"bytes\":null,\"errorCode\":null,"
                        + "\"errorMessage\":null,\"streamed\":\"undeux\",\"streamTruncated\":false}");
            }
        });
        List<String> chunks = new ArrayList<>();

        RunnerCallResult result = call(node, chunks::add);

        assertThat(chunks).containsExactly("un", "deux");
        assertThat(result.ok()).isTrue();
        assertThat(result.content()).isEqualTo("fini");
        assertThat(result.exitCode()).isZero();
        assertThat(result.durationMs()).isEqualTo(42L);
        assertThat(result.bytes()).isNull();
        // Le `streamed` vient de la ligne `result`, jamais d'une ré-agrégation des fragments.
        assertThat(result.streamed()).isEqualTo("undeux");
        assertThat(presentedSecret.get()).isEqualTo(SECRET);
        assertThat(presentedOrigin.get()).isNotBlank();
    }

    @Test
    void unauthorizedPeerDegradesToRunnerNotOnThisNode() {
        RemoteRunnerNode node = peer(exchange -> exchange.sendResponseHeaders(401, -1));

        RunnerCallResult result = call(node, null);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE);
        assertThat(result.streamed()).isEmpty();
    }

    @Test
    void serverErrorDegradesToRunnerNotOnThisNode() {
        RemoteRunnerNode node = peer(exchange -> exchange.sendResponseHeaders(503, -1));

        assertThat(call(node, null).errorCode())
                .isEqualTo(RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE);
    }

    @Test
    void nonNdjsonBodyDegradesToRunnerNotOnThisNode() {
        RemoteRunnerNode node = peer(exchange -> {
            byte[] body = "<html>pas du ndjson</html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        assertThat(call(node, null).errorCode())
                .isEqualTo(RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE);
    }

    @Test
    void streamCutBeforeResultDegradesToRunnerUnavailable() {
        RemoteRunnerNode node = peer(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                line(out, "{\"type\":\"stream\",\"chunk\":\"debut\"}");
            }
        });
        List<String> chunks = new ArrayList<>();

        RunnerCallResult result = call(node, chunks::add);

        // Les fragments déjà relayés restent affichés ; le modèle, lui, reçoit l'erreur.
        assertThat(chunks).containsExactly("debut");
        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.RUNNER_UNAVAILABLE);
        assertThat(result.streamed()).isEmpty();
    }

    @Test
    void unreachablePeerDegradesToRunnerNotOnThisNodeWithoutRetrying() {
        // Port fermé : aucune tentative supplémentaire, rejouer un write_file serait destructeur.
        RemoteRunnerNode node = new RemoteRunnerNode("node-mort", "http://127.0.0.1:1");

        assertThat(call(node, null).errorCode())
                .isEqualTo(RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE);
    }

    @Test
    void unknownLineTypeIsIgnoredForForwardCompatibility() {
        RemoteRunnerNode node = peer(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                line(out, "{\"type\":\"quelque_chose_de_plus_recent\"}");
                line(out, "{\"type\":\"result\",\"ok\":false,\"content\":\"\",\"truncated\":false,"
                        + "\"exitCode\":null,\"durationMs\":3,\"bytes\":null,"
                        + "\"errorCode\":\"path_denied\",\"errorMessage\":\"refus\","
                        + "\"streamed\":\"\",\"streamTruncated\":false}");
            }
        });

        RunnerCallResult result = call(node, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("path_denied");
        assertThat(result.errorMessage()).isEqualTo("refus");
    }

    @Test
    void aSilentPeerTimesOutAndIsAskedToCancelWhatItStillRuns() throws Exception {
        // F-38 / SF-38-13 : le pair muet tient peut-être encore une commande sur la machine de
        // l'utilisateur. On dégrade en RUNNER_TIMEOUT et on lui demande de l'arrêter — best-effort,
        // une seule fois, jamais de nouvelle tentative d'appel.
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        java.util.concurrent.CountDownLatch cancelled = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/api/internal/runner/cancel", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"cancelled\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
            cancelled.countDown();
        });
        RunnerRelayProperties properties = new RunnerRelayProperties();
        properties.setSecret(SECRET);
        properties.setConnectTimeoutMs(2_000L);
        properties.setReadTimeoutMs(400L);
        properties.setBroadcastTimeoutMs(2_000L);
        client = new RunnerRelayClient(properties, new RelayPeerClient(properties, objectMapper),
                objectMapper);
        RemoteRunnerNode node = peer(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            line(out, "{\"type\":\"stream\",\"chunk\":\"debut\"}");
            try {
                Thread.sleep(2_000L); // Silence prolongé : aucune ligne `result` n'arrive à temps.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            out.close();
        });

        RunnerCallResult result = call(node, null);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.RUNNER_TIMEOUT);
        assertThat(cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    @FunctionalInterface
    private interface PeerBehaviour {
        void serve(HttpExchange exchange) throws IOException;
    }
}
