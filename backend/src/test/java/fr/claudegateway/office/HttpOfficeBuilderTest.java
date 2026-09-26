package fr.claudegateway.office;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import fr.claudegateway.diagrams.DiagramProperties;

/**
 * F-129 / SF-129-07 — l'appel au service, éprouvé sur un vrai serveur HTTP local.
 *
 * <p>Ce que ces tests protègent : le <b>format vient de l'outil</b> (jamais d'un champ libre du
 * modèle), les bornes sont tenues <b>de ce côté-ci aussi</b>, et les deux échecs restent distincts —
 * une description à corriger n'est pas un service muet.</p>
 */
class HttpOfficeBuilderTest {

    private HttpServer server;
    private HttpOfficeBuilder builder;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> received = new AtomicReference<>("");

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        DiagramProperties properties = new DiagramProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        builder = new HttpOfficeBuilder(properties, mapper);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void serve(int status, byte[] body) {
        server.createContext("/document", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    private JsonNode spec(String field) {
        return mapper.createObjectNode().set(field,
                mapper.createArrayNode().add(mapper.createObjectNode().put("type", "text")));
    }

    @Test
    @DisplayName("LE CRITÈRE : le fichier revient, et le FORMAT envoyé vient de l'outil appelé")
    void thefileComesBackAndTheFormatComesFromTheTool() {
        serve(200, "DOCX".getBytes(StandardCharsets.UTF_8));

        byte[] file = builder.build(OfficeFormat.DOCX, spec("blocks"));

        assertThat(new String(file, StandardCharsets.UTF_8)).isEqualTo("DOCX");
        assertThat(received.get()).contains("\"format\":\"docx\"").contains("blocks");
    }

    @Test
    @DisplayName("le classeur emprunte le MÊME chemin, avec son propre champ de contenu")
    void thespreadsheetTakesTheSamePath() {
        serve(200, "XLSX".getBytes(StandardCharsets.UTF_8));

        builder.build(OfficeFormat.XLSX, spec("sheets"));

        assertThat(received.get()).contains("\"format\":\"xlsx\"").contains("sheets");
    }

    @Test
    @DisplayName("une description sans contenu est refusée SANS appeler le service")
    void anemptySpecNeverReachesTheService() {
        assertThatThrownBy(() -> builder.build(OfficeFormat.DOCX, mapper.createObjectNode()))
                .isInstanceOf(OfficeRejectedException.class)
                .hasMessageContaining("blocks");
        assertThatThrownBy(() -> builder.build(OfficeFormat.XLSX, null))
                .isInstanceOf(OfficeRejectedException.class)
                .hasMessageContaining("sheets");
        assertThat(received.get()).isEmpty();
    }

    @Test
    @DisplayName("un 4xx du service est une description à CORRIGER, et la raison est reprise")
    void afourHundredIsARejection() {
        serve(422, "{\"error\":\"Bloc 2 : type inconnu « video ».\"}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> builder.build(OfficeFormat.DOCX, spec("blocks")))
                .isInstanceOf(OfficeRejectedException.class)
                .hasMessageContaining("type inconnu");
    }

    @Test
    @DisplayName("un 5xx, un fichier vide ou un service muet sont des INDISPONIBILITÉS, pas des refus")
    void afiveHundredIsAnUnavailability() {
        serve(500, new byte[0]);

        assertThatThrownBy(() -> builder.build(OfficeFormat.DOCX, spec("blocks")))
                .isInstanceOf(OfficeBuilderUnavailableException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("un fichier vide n'est pas un document")
    void anemptyFileIsRefused() {
        serve(200, new byte[0]);

        assertThatThrownBy(() -> builder.build(OfficeFormat.DOCX, spec("blocks")))
                .isInstanceOf(OfficeBuilderUnavailableException.class)
                .hasMessageContaining("vide");
    }

    @Test
    @DisplayName("la borne du fichier est tenue ICI AUSSI : un document démesuré est refusé")
    void ahugeFileIsRefused() {
        serve(200, new byte[HttpOfficeBuilder.MAX_FILE_BYTES + 1]);

        assertThatThrownBy(() -> builder.build(OfficeFormat.DOCX, spec("blocks")))
                .isInstanceOf(OfficeRejectedException.class)
                .hasMessageContaining("trop lourd");
    }

    @Test
    @DisplayName("sans service configuré, rien n'est promis")
    void withoutAServiceNothingIsPromised() {
        HttpOfficeBuilder none = new HttpOfficeBuilder(new DiagramProperties(), mapper);

        assertThat(none.isAvailable()).isFalse();
        assertThatThrownBy(() -> none.build(OfficeFormat.DOCX, spec("blocks")))
                .isInstanceOf(OfficeBuilderUnavailableException.class)
                .hasMessageContaining("pas configurée");
    }
}
