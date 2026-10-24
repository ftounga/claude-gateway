package fr.claudegateway.atelier.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import fr.claudegateway.diagrams.DiagramProperties;
import fr.claudegateway.diagrams.DiagramRejectedException;
import fr.claudegateway.diagrams.DiagramRendererUnavailableException;

/** F-212 / SF-212-02 — l'appel de {@code /terminal-shot}, sur un vrai serveur HTTP local. */
class HttpTerminalShotRendererTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;
    private DiagramProperties properties;
    private HttpTerminalShotRenderer renderer;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties = new DiagramProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        renderer = new HttpTerminalShotRenderer(properties, MAPPER);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void serve(int status, String body) {
        server.createContext("/terminal-shot", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    private static JsonNode session() throws IOException {
        return MAPPER.readTree("{\"os\":\"linux\",\"commands\":[{\"command\":\"ls\",\"stdout\":\"a\\n\"}]}");
    }

    @Test
    @DisplayName("nominal : le relevé part tel quel, les images reviennent décodées")
    void nominal() throws IOException {
        String png = Base64.getEncoder().encodeToString("PNG".getBytes(StandardCharsets.UTF_8));
        serve(200, "{\"images\":[{\"png\":\"" + png + "\",\"width\":800,\"height\":400}]}");
        List<TerminalShotRenderer.Shot> shots = renderer.shoot(session());
        assertThat(shots).hasSize(1);
        assertThat(new String(shots.get(0).png(), StandardCharsets.UTF_8)).isEqualTo("PNG");
        assertThat(shots.get(0).width()).isEqualTo(800);
        assertThat(lastBody.get()).contains("\"command\":\"ls\"");
    }

    @Test
    @DisplayName("relevé refusé (400) : la raison du service remonte")
    void rejected() throws IOException {
        serve(400, "{\"error\":\"Il faut 1 à 8 commandes.\"}");
        assertThatThrownBy(() -> renderer.shoot(session()))
                .isInstanceOf(DiagramRejectedException.class).hasMessageContaining("1 à 8");
    }

    @Test
    @DisplayName("service en panne (500) ou non configuré : indisponible")
    void unavailable() throws IOException {
        serve(500, "{}");
        assertThatThrownBy(() -> renderer.shoot(session())).isInstanceOf(DiagramRendererUnavailableException.class);
        properties.setBaseUrl("");
        assertThat(renderer.isAvailable()).isFalse();
        assertThatThrownBy(() -> renderer.shoot(session())).isInstanceOf(DiagramRendererUnavailableException.class);
    }

    @Test
    @DisplayName("relevé sans commande : refusé avant tout appel")
    void emptySession() {
        assertThatThrownBy(() -> renderer.shoot(MAPPER.createObjectNode()))
                .isInstanceOf(DiagramRejectedException.class);
    }
}
