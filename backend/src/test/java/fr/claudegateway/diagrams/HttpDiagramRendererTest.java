package fr.claudegateway.diagrams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import fr.claudegateway.diagrams.DiagramRenderer.Format;

/**
 * F-142 / SF-142-06 — l'appel au service de rendu, éprouvé sur un vrai serveur HTTP local.
 *
 * <p>Ce que ces tests protègent : les bornes sont appliquées <b>avant</b> tout appel, et les deux
 * familles d'échec restent <b>distinctes</b> — un diagramme invalide n'est pas un service en panne.</p>
 */
class HttpDiagramRendererTest {

    private HttpServer server;
    private DiagramProperties properties;
    private HttpDiagramRenderer renderer;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties = new DiagramProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        renderer = new HttpDiagramRenderer(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void serve(int status, String contentType, byte[] body) {
        server.createContext("/render", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @Test
    @DisplayName("nominal : l'image revient, et le code du diagramme est bien ce qui a été envoyé")
    void rendersAnImage() {
        serve(200, "image/png", "PNG-BYTES".getBytes(StandardCharsets.UTF_8));

        DiagramRenderer.Rendered rendered = renderer.render("flowchart TD\n A-->B", Format.PNG, 1200);

        assertThat(rendered.format()).isEqualTo(Format.PNG);
        assertThat(new String(rendered.bytes(), StandardCharsets.UTF_8)).isEqualTo("PNG-BYTES");
        assertThat(lastBody.get()).contains("flowchart TD").contains("\"width\":1200").contains("\"format\":\"png\"");
    }

    @Test
    @DisplayName("diagramme invalide (422) : la RAISON du moteur remonte, en DiagramRejectedException")
    void aninvalidDiagramCarriesTheReason() {
        serve(422, "application/json", "{\"error\":\"Parse error on line 2\"}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> renderer.render("flowchart TD\n A--", Format.PNG, null))
                .isInstanceOf(DiagramRejectedException.class)
                .hasMessageContaining("Parse error on line 2");
    }

    @Test
    @DisplayName("service en panne (500) : DiagramRendererUnavailableException — pas la faute du diagramme")
    void abrokenServiceIsNotTheDiagramsFault() {
        serve(500, "application/json", "{}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> renderer.render("flowchart TD\n A-->B", Format.PNG, null))
                .isInstanceOf(DiagramRendererUnavailableException.class);
    }

    @Test
    @DisplayName("service injoignable : dit indisponible, sans faire attendre indéfiniment")
    void anunreachableServiceIsSaid() {
        properties.setBaseUrl("http://127.0.0.1:1");

        assertThatThrownBy(() -> renderer.render("flowchart TD\n A-->B", Format.PNG, null))
                .isInstanceOf(DiagramRendererUnavailableException.class);
    }

    @Test
    @DisplayName("BORNES appliquées AVANT tout appel : code vide, code trop long, image trop lourde")
    void boundsAreAppliedBeforeCalling() {
        properties.setMaxCodeChars(10);

        assertThatThrownBy(() -> renderer.render("   ", Format.PNG, null))
                .isInstanceOf(DiagramRejectedException.class)
                .hasMessageContaining("Aucun code");
        assertThatThrownBy(() -> renderer.render("flowchart TD A-->B", Format.PNG, null))
                .isInstanceOf(DiagramRejectedException.class)
                .hasMessageContaining("trop long");
        // Le serveur n'a jamais été démarré : ces refus n'ont donc appelé personne.

        properties.setMaxCodeChars(20_000);
        properties.setMaxImageBytes(4);
        serve(200, "image/png", "PNG-BYTES".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> renderer.render("flowchart TD\n A-->B", Format.PNG, null))
                .isInstanceOf(DiagramRejectedException.class)
                .hasMessageContaining("trop lourde");
    }

    @Test
    @DisplayName("sans service configuré, l'outil n'est pas disponible — on ne promet rien")
    void withoutConfigurationNothingIsPromised() {
        DiagramProperties empty = new DiagramProperties();
        HttpDiagramRenderer off = new HttpDiagramRenderer(empty, new ObjectMapper());

        assertThat(off.isAvailable()).isFalse();
        assertThatThrownBy(() -> off.render("flowchart TD\n A-->B", Format.PNG, null))
                .isInstanceOf(DiagramRendererUnavailableException.class);
    }
}
