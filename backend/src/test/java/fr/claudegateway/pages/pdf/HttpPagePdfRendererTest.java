package fr.claudegateway.pages.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import fr.claudegateway.diagrams.DiagramProperties;

/** L'appel au moteur PDF, éprouvé sur un vrai serveur HTTP local (F-184 / SF-184-02). */
class HttpPagePdfRendererTest {

    private HttpServer server;
    private DiagramProperties properties;
    private HttpPagePdfRenderer renderer;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties = new DiagramProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        renderer = new HttpPagePdfRenderer(properties, new ObjectMapper(), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void serve(int status, String missing, byte[] body) {
        server.createContext("/pdf", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (missing != null) {
                exchange.getResponseHeaders().add("X-Cg-Missing-Resources", missing);
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @Test
    @DisplayName("200 : le PDF et les manquants reviennent ; le lot part en JSON, ressources en base64")
    void prints() {
        serve(200, "https://a.example/x.js", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));

        PagePdfRenderer.Printed printed = renderer.print("<h1>Radar</h1>", List.of(new PagePdfRenderer.Resource(
                PagePdfRenderer.ORIGIN + "/capture.png", "image/png", new byte[] {1, 2, 3})));

        assertThat(new String(printed.pdf(), StandardCharsets.US_ASCII)).isEqualTo("%PDF-1.7");
        assertThat(printed.missing()).isEqualTo("https://a.example/x.js");
        assertThat(lastBody.get()).contains("\"html\":\"<h1>Radar</h1>\"")
                .contains("\"url\":\"https://page.cg.local/capture.png\"")
                .contains("\"body\":\"AQID\"");
    }

    @Test
    @DisplayName("413 / 422 : refus du moteur, avec sa raison")
    void rejected() {
        serve(413, null, "{\"error\":\"Trop de ressources : 61 (60 au plus).\"}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> renderer.print("<h1>x</h1>", List.of()))
                .isInstanceOf(PagePdfRejectedException.class)
                .hasMessageContaining("Trop de ressources");
    }

    @Test
    @DisplayName("500 ou 504 : indisponible")
    void failing() {
        serve(504, null, "{\"error\":\"Impression trop longue (45 s).\"}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> renderer.print("<h1>x</h1>", List.of()))
                .isInstanceOf(PagePdfUnavailableException.class);
    }

    @Test
    @DisplayName("service muet ou non configuré : indisponible")
    void unreachableOrOff() {
        server.stop(0);
        assertThatThrownBy(() -> renderer.print("<h1>x</h1>", List.of()))
                .isInstanceOf(PagePdfUnavailableException.class);
        properties.setBaseUrl("");
        assertThat(renderer.isAvailable()).isFalse();
        assertThatThrownBy(() -> renderer.print("<h1>x</h1>", List.of()))
                .isInstanceOf(PagePdfUnavailableException.class);
    }
}
