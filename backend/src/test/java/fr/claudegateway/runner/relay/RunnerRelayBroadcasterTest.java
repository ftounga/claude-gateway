package fr.claudegateway.runner.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * Diffusion des gestes inter-pods (F-38 / SF-38-13) face à des pairs réels — un {@code HttpServer} de
 * la JDK, parce que ce qui compte ici est le comportement du groupe : un pair qui résout, un pair qui
 * ne résout pas, un pair injoignable.
 *
 * <p>Ce que ces tests protègent : une diffusion partielle n'est <b>pas</b> un échec (c'est le cas
 * nominal d'un cluster qui bouge), et le relais éteint ne parle à personne — le chemin mono-pod reste
 * exactement celui d'avant SF-38-13.</p>
 */
class RunnerRelayBroadcasterTest {

    private static final String SECRET = "secret-de-relais-de-test-32-octets!!";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private final List<String> receivedSecrets = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Démarre un pair qui répond {@code body} sur {@code path}, et rend son adresse de base. */
    private String peer(String path, String body) {
        server.createContext(path, exchange -> {
            receivedSecrets.add(exchange.getRequestHeaders()
                    .getFirst(RunnerRelayAuthFilter.SECRET_HEADER));
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Diffuseur dont la liste de pairs est imposée : la résolution DNS a son propre test. */
    private RunnerRelayBroadcaster broadcaster(List<String> peers) {
        RunnerRelayProperties properties = new RunnerRelayProperties();
        properties.setSecret(SECRET);
        properties.setConnectTimeoutMs(2_000L);
        properties.setBroadcastTimeoutMs(3_000L);
        RelayPeerResolver resolver = new RelayPeerResolver(properties) {
            @Override
            public List<String> peerBaseUrls() {
                return peers;
            }
        };
        return new RunnerRelayBroadcaster(properties, resolver,
                new RelayPeerClient(properties, objectMapper), objectMapper);
    }

    @Test
    void aPeerHoldingTheGateResolvesTheConfirmation() {
        String peer = peer("/api/internal/runner/confirm", "{\"resolved\":true}");

        boolean resolved = broadcaster(List.of(peer))
                .broadcastConfirm(UUID.randomUUID(), UUID.randomUUID(), "toolu_1", true, "vas-y");

        assertThat(resolved).isTrue();
        assertThat(receivedSecrets).containsExactly(SECRET);
        JsonNode sent = read(receivedBodies.get(0));
        assertThat(sent.path("callId").asText()).isEqualTo("toolu_1");
        assertThat(sent.path("allow").asBoolean()).isTrue();
        assertThat(sent.path("reason").asText()).isEqualTo("vas-y");
    }

    @Test
    void noPeerHoldingTheGateMeansTheCallerKeepsItsOwnError() {
        // Personne n'a tranché : l'appelant relance son 409, et la porte qui attendrait sans être
        // atteinte expirera en refus. Le silence ne vaut jamais autorisation.
        String peer = peer("/api/internal/runner/confirm", "{\"resolved\":false}");

        assertThat(broadcaster(List.of(peer))
                .broadcastConfirm(UUID.randomUUID(), UUID.randomUUID(), "toolu_1", true, null))
                .isFalse();
    }

    @Test
    void anUnreachablePeerDoesNotPreventTheOthersFromResolving() {
        String peer = peer("/api/internal/runner/confirm", "{\"resolved\":true}");

        boolean resolved = broadcaster(List.of("http://127.0.0.1:1", peer))
                .broadcastConfirm(UUID.randomUUID(), UUID.randomUUID(), "toolu_1", false, null);

        assertThat(resolved).isTrue();
    }

    @Test
    void aDisabledRelayNeverTalksToAnyone() {
        peer("/api/internal/runner/confirm", "{\"resolved\":true}");

        assertThat(RunnerRelayBroadcaster.disabled()
                .broadcastConfirm(UUID.randomUUID(), UUID.randomUUID(), "toolu_1", true, null))
                .isFalse();
        assertThat(receivedBodies).isEmpty();
    }

    @Test
    void peersThatAreAllUnreachableNeverFailTheCaller() {
        // Best-effort : l'utilisateur qui demande l'arrêt reçoit sa réponse même si aucun pair ne
        // répond. Une panne de diffusion dégrade vers le comportement d'avant SF-38-13, jamais vers
        // une erreur inventée.
        RunnerRelayBroadcaster broadcaster =
                broadcaster(List.of("http://127.0.0.1:1", "http://127.0.0.1:2"));

        broadcaster.broadcastInterrupt(UUID.randomUUID(), UUID.randomUUID(), "user_interrupt");
        broadcaster.broadcastSessionInterrupt("sess_1", true);

        assertThat(broadcaster.broadcastConfirm(UUID.randomUUID(), UUID.randomUUID(), "toolu_1",
                true, null)).isFalse();
    }

    @Test
    void interruptCarriesTheTurnKeyAndTheReason() {
        String peer = peer("/api/internal/atelier/interrupt",
                "{\"marked\":true,\"released\":0,\"cancelled\":0}");
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        broadcaster(List.of(peer)).broadcastInterrupt(userId, workspaceId, "user_interrupt");

        JsonNode sent = read(receivedBodies.get(0));
        assertThat(sent.path("userId").asText()).isEqualTo(userId.toString());
        assertThat(sent.path("workspaceId").asText()).isEqualTo(workspaceId.toString());
        assertThat(sent.path("reason").asText()).isEqualTo("user_interrupt");
    }

    @Test
    void sessionInterruptCarriesTheSessionIdAndTheMark() {
        String peer = peer("/api/internal/atelier/session-interrupt", "{\"marked\":false}");

        broadcaster(List.of(peer)).broadcastSessionInterrupt("sess_1", false);

        JsonNode sent = read(receivedBodies.get(0));
        assertThat(sent.path("sessionId").asText()).isEqualTo("sess_1");
        assertThat(sent.path("mark").asBoolean()).isFalse();
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }
}
