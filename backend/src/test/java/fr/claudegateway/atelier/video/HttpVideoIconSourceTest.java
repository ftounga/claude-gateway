package fr.claudegateway.atelier.video;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import fr.claudegateway.diagrams.DiagramProperties;
import fr.claudegateway.diagrams.DiagramRejectedException;
import fr.claudegateway.diagrams.DiagramRendererUnavailableException;

/** F-215 / SF-215-03 — l'appel de {@code /icons}, sur un vrai serveur HTTP local. */
class HttpVideoIconSourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;
    private DiagramProperties properties;
    private HttpVideoIconSource source;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties = new DiagramProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        source = new HttpVideoIconSource(properties, MAPPER);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void serve(int status, String body) {
        server.createContext("/icons", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @Test
    @DisplayName("recherche : les noms reviennent tels quels")
    void search() {
        serve(200, "{\"trouvees\":[\"aws/network/route-53\",\"aws/network/route-53-hosted-zone\"]}");
        assertThat(source.search("route 53")).containsExactly("aws/network/route-53", "aws/network/route-53-hosted-zone");
        assertThat(lastBody.get()).contains("\"chercher\":\"route 53\"");
    }

    @Test
    @DisplayName("lecture : PNG décodés, manquantes et invalides rapportées, base64 illisible = manquante")
    void fetch() {
        String png = Base64.getEncoder().encodeToString("PNG".getBytes(StandardCharsets.UTF_8));
        serve(200, "{\"icones\":[{\"nom\":\"k8s/compute/pod\",\"png\":\"" + png + "\"},"
                + "{\"nom\":\"aws/network/bad\",\"png\":\"%%%\"}],"
                + "\"manquantes\":[\"aws/network/nope\"],\"invalides\":[\"../x\"]}");
        VideoIconSource.Batch batch = source.fetch(List.of("k8s/compute/pod", "aws/network/bad", "aws/network/nope", "../x"));
        assertThat(batch.icons()).extracting(VideoIconSource.Icon::name).containsExactly("k8s/compute/pod");
        assertThat(new String(batch.icons().get(0).png(), StandardCharsets.UTF_8)).isEqualTo("PNG");
        assertThat(batch.missing()).containsExactly("aws/network/nope", "aws/network/bad");
        assertThat(batch.invalid()).containsExactly("../x");
        assertThat(lastBody.get()).contains("\"noms\":[");
    }

    @Test
    @DisplayName("400 : la raison du service remonte ; 503 : indisponible ; non configuré : indisponible")
    void errors() {
        serve(400, "{\"error\":\"chercher : 1 à 60 caractères.\"}");
        assertThatThrownBy(() -> source.search("")).isInstanceOf(DiagramRejectedException.class)
                .hasMessageContaining("1 à 60");
        properties.setBaseUrl("");
        assertThat(source.isAvailable()).isFalse();
        assertThatThrownBy(() -> source.search("x")).isInstanceOf(DiagramRendererUnavailableException.class);
    }

    @Test
    @DisplayName("503 : service sans lib diagrams")
    void unavailable() {
        serve(503, "{\"error\":\"Icônes indisponibles\"}");
        assertThatThrownBy(() -> source.fetch(List.of("k8s/compute/pod")))
                .isInstanceOf(DiagramRendererUnavailableException.class).hasMessageContaining("503");
    }
}
