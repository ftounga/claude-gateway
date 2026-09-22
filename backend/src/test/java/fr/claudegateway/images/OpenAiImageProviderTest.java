package fr.claudegateway.images;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import fr.claudegateway.images.ImageProvider.GeneratedImageData;

/**
 * L'implémentation HTTP compatible OpenAI (F-142 / SF-142-04). Éteint-par-défaut (aucun appel) et chemin
 * configuré via un petit serveur HTTP local — jamais de vrai réseau.
 */
class OpenAiImageProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PNG_B64 = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4, 5});

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static ImageGenerationProperties props(String baseUrl, String key, String model) {
        return new ImageGenerationProperties(baseUrl, key, model, Duration.ofSeconds(10), null, null, null,
                null, null);
    }

    @Test
    @DisplayName("non configuré (pas de clé) : lève l'exception nommée, AUCUN appel réseau")
    void notConfiguredThrowsWithoutCalling() {
        AtomicInteger hits = new AtomicInteger();
        String baseUrl = startServer(hits, 200, "{}", new AtomicReference<>());
        OpenAiImageProvider provider = new OpenAiImageProvider(props(baseUrl, null, null),
                RestClient.builder(), MAPPER);

        assertThatThrownBy(() -> provider.generate("un chat", ImageSize.SQUARE))
                .isInstanceOf(ImageProviderUnavailableException.class);
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("configuré : POST /images/generations, b64_json décodé en octets, Bearer transmis")
    void configuredDecodesImage() {
        AtomicReference<String> auth = new AtomicReference<>();
        String body = "{\"data\":[{\"b64_json\":\"" + PNG_B64 + "\"}]}";
        String baseUrl = startServer(new AtomicInteger(), 200, body, auth);
        OpenAiImageProvider provider = new OpenAiImageProvider(props(baseUrl, "sk-secret", "gpt-image-1"),
                RestClient.builder(), MAPPER);

        GeneratedImageData data = provider.generate("une couverture bleue", ImageSize.LANDSCAPE);

        assertThat(data.contentType()).isEqualTo("image/png");
        assertThat(data.bytes()).containsExactly(1, 2, 3, 4, 5);
        assertThat(auth.get()).isEqualTo("Bearer sk-secret");
    }

    @Test
    @DisplayName("gpt-image-1 : le prompt et la taille partent en corps JSON, PAS de response_format")
    void gptImageSendsPromptAsData() {
        AtomicReference<String> captured = new AtomicReference<>();
        String responseBody = "{\"data\":[{\"b64_json\":\"" + PNG_B64 + "\"}]}";
        String baseUrl = startServerCapturingBody(200, responseBody, captured);
        OpenAiImageProvider provider = new OpenAiImageProvider(props(baseUrl, "k", "gpt-image-1"),
                RestClient.builder(), MAPPER);

        provider.generate("un renard décoratif", ImageSize.PORTRAIT);

        assertThat(captured.get()).contains("\"prompt\":\"un renard décoratif\"");
        assertThat(captured.get()).contains("\"size\":\"1024x1536\"").contains("\"model\":\"gpt-image-1\"");
        assertThat(captured.get()).doesNotContain("response_format");
    }

    @Test
    @DisplayName("dall-e-3 : response_format=b64_json demandé explicitement")
    void dalleAsksForB64() {
        AtomicReference<String> captured = new AtomicReference<>();
        String responseBody = "{\"data\":[{\"b64_json\":\"" + PNG_B64 + "\"}]}";
        String baseUrl = startServerCapturingBody(200, responseBody, captured);
        OpenAiImageProvider provider = new OpenAiImageProvider(props(baseUrl, "k", "dall-e-3"),
                RestClient.builder(), MAPPER);

        provider.generate("bandeau", ImageSize.SQUARE);

        assertThat(captured.get()).contains("\"response_format\":\"b64_json\"");
    }

    @Test
    @DisplayName("erreur du fournisseur (500) : ImageProviderException")
    void providerErrorThrows() {
        String baseUrl = startServer(new AtomicInteger(), 500, "boom", new AtomicReference<>());
        OpenAiImageProvider provider = new OpenAiImageProvider(props(baseUrl, "k", "gpt-image-1"),
                RestClient.builder(), MAPPER);

        assertThatThrownBy(() -> provider.generate("x", ImageSize.SQUARE))
                .isInstanceOf(ImageProviderException.class);
    }

    @Test
    @DisplayName("réponse sans image (data vide) : ImageProviderException")
    void emptyDataThrows() {
        String baseUrl = startServer(new AtomicInteger(), 200, "{\"data\":[]}", new AtomicReference<>());
        OpenAiImageProvider provider = new OpenAiImageProvider(props(baseUrl, "k", "gpt-image-1"),
                RestClient.builder(), MAPPER);

        assertThatThrownBy(() -> provider.generate("x", ImageSize.SQUARE))
                .isInstanceOf(ImageProviderException.class);
    }

    private String startServer(AtomicInteger hits, int status, String body, AtomicReference<String> auth) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/images/generations", exchange -> {
                hits.incrementAndGet();
                auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.getRequestBody().readAllBytes();
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            });
            server.start();
            return "http://127.0.0.1:" + server.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException("Serveur HTTP de test non démarré", e);
        }
    }

    private String startServerCapturingBody(int status, String responseBody, AtomicReference<String> captured) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/images/generations", exchange -> {
                captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            });
            server.start();
            return "http://127.0.0.1:" + server.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException("Serveur HTTP de test non démarré", e);
        }
    }
}
