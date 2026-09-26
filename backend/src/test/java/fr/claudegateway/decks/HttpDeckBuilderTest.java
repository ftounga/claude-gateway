package fr.claudegateway.decks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import fr.claudegateway.diagrams.DiagramProperties;

/**
 * F-129 / SF-129-06 — la réponse du service, éprouvée sur un vrai serveur HTTP local.
 *
 * <p>Ce que ces tests protègent : les <b>deux formes</b> de réponse restent lues (le deck seul, le
 * deck avec son aperçu), et les bornes de l'aperçu sont tenues <b>de ce côté-ci aussi</b> — une
 * borne tenue d'un seul côté finit par ne plus être tenue du tout.</p>
 */
class HttpDeckBuilderTest {

    private HttpServer server;
    private HttpDeckBuilder builder;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        DiagramProperties properties = new DiagramProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        builder = new HttpDeckBuilder(properties, mapper);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void serve(int status, String contentType, byte[] body) {
        server.createContext("/presentation", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    private com.fasterxml.jackson.databind.JsonNode spec() {
        return mapper.createObjectNode().set("slides",
                mapper.createArrayNode().add(mapper.createObjectNode().put("type", "bullets")));
    }

    private String json(String pptx, int slides, int imageBytes, String error) {
        String image = Base64.getEncoder().encodeToString(new byte[imageBytes]);
        String list = IntStream.range(0, slides).mapToObj(i -> "\"" + image + "\"")
                .collect(Collectors.joining(","));
        return "{\"pptx\":\"" + Base64.getEncoder().encodeToString(pptx.getBytes(StandardCharsets.UTF_8))
                + "\",\"slides\":[" + list + "],\"previewError\":\"" + error + "\"}";
    }

    @Test
    @DisplayName("LE CRITÈRE : la réponse avec aperçu rend le fichier ET ses images, dans l'ordre")
    void readsTheDeckAndItsPreview() {
        serve(200, "application/json; charset=utf-8",
                json("PPTX", 3, 64, "").getBytes(StandardCharsets.UTF_8));

        DeckBuilder.Deck deck = builder.build(spec());

        assertThat(new String(deck.bytes(), StandardCharsets.UTF_8)).isEqualTo("PPTX");
        assertThat(deck.slides()).hasSize(3);
        assertThat(deck.slides().get(0)).hasSize(64);
        assertThat(deck.previewError()).isEmpty();
    }

    @Test
    @DisplayName("COMPATIBILITÉ : une réponse binaire reste lue comme avant, sans aperçu")
    void readsTheHistoricalBinaryResponse() {
        serve(200, "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "PPTX".getBytes(StandardCharsets.UTF_8));

        DeckBuilder.Deck deck = builder.build(spec());

        assertThat(new String(deck.bytes(), StandardCharsets.UTF_8)).isEqualTo("PPTX");
        assertThat(deck.slides()).isEmpty();
    }

    @Test
    @DisplayName("l'échec d'aperçu est DIT, et le fichier est quand même rendu")
    void apreviewFailureIsNamedAndTheDeckSurvives() {
        serve(200, "application/json", json("PPTX", 0, 0, "chromium absent")
                .getBytes(StandardCharsets.UTF_8));

        DeckBuilder.Deck deck = builder.build(spec());

        assertThat(deck.bytes()).isNotEmpty();
        assertThat(deck.slides()).isEmpty();
        assertThat(deck.previewError()).isEqualTo("chromium absent");
    }

    @Test
    @DisplayName("BORNE : une image d'aperçu au-dessus de la borne est refusée, avec sa taille")
    void anoversizedPreviewImageIsRefused() {
        serve(200, "application/json",
                json("PPTX", 1, HttpDeckBuilder.MAX_PREVIEW_BYTES + 1, "")
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> builder.build(spec()))
                .isInstanceOf(DeckRejectedException.class)
                .hasMessageContaining("trop lourde");
    }

    @Test
    @DisplayName("BORNE : plus d'images que de slides permises est refusé")
    void toomanyPreviewImagesAreRefused() {
        serve(200, "application/json",
                json("PPTX", HttpDeckBuilder.MAX_PREVIEW_SLIDES + 1, 8, "")
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> builder.build(spec()))
                .isInstanceOf(DeckRejectedException.class)
                .hasMessageContaining("Aperçu refusé");
    }

    @Test
    @DisplayName("un refus du service reste un refus NOMMÉ, pas une panne")
    void arefusalStaysARefusal() {
        serve(422, "application/json",
                "{\"error\":\"Slide 2 : type inconnu\"}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> builder.build(spec()))
                .isInstanceOf(DeckRejectedException.class)
                .hasMessageContaining("type inconnu");
    }

    @Test
    @DisplayName("sans description portant des slides, aucun appel n'est fait")
    void anemptySpecIsRefusedBeforeAnyCall() {
        assertThatThrownBy(() -> builder.build(mapper.createObjectNode()))
                .isInstanceOf(DeckRejectedException.class);
    }
}
