package fr.claudegateway.teams.meeting.stt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

import fr.claudegateway.teams.meeting.stt.TranscriptionProvider.Transcript;

/**
 * L'implémentation HTTP compatible Whisper (F-128 / SF-128-04). On teste l'éteint-par-défaut (aucun
 * appel) et le chemin configuré via un petit serveur HTTP local (pas de mock RestClient fragile).
 */
class HttpTranscriptionProviderTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("non configuré (pas de base-url/clé) : lève l'exception nommée, AUCUN appel réseau")
    void notConfiguredThrowsWithoutCalling() {
        AtomicInteger hits = new AtomicInteger();
        String baseUrl = startServer(hits, 200, "{}");
        // Propriétés VIDES => éteint, même si un serveur existe : on ne doit jamais l'appeler.
        TranscriptionProperties off = new TranscriptionProperties(null, null, null, null, null, null);
        HttpTranscriptionProvider provider = new HttpTranscriptionProvider(off, RestClient.builder());

        assertThatThrownBy(() -> provider.transcribe(new byte[] {1, 2, 3}, "audio/webm", null))
                .isInstanceOf(TranscriptionProviderUnavailableException.class);
        assertThat(hits.get()).isZero();
        // Un serveur non joignable ne doit rien changer : baseUrl inutilisé.
        assertThat(baseUrl).isNotBlank();
    }

    @Test
    @DisplayName("configuré : POST multipart, transcript horodaté depuis les segments + langue")
    void configuredParsesSegments() {
        AtomicReference<String> auth = new AtomicReference<>();
        String body = """
                {"text":"bonjour le monde","language":"fr",
                 "segments":[{"start":0.0,"end":2.0,"text":" Bonjour"},
                             {"start":65.4,"end":70.0,"text":" le monde"}]}""";
        String baseUrl = startServer(new AtomicInteger(), 200, body, auth);
        TranscriptionProperties on = new TranscriptionProperties(baseUrl, "secret-key", "whisper-1", "fr",
                Duration.ofSeconds(10), null);
        HttpTranscriptionProvider provider = new HttpTranscriptionProvider(on, RestClient.builder());

        Transcript transcript = provider.transcribe("opus".getBytes(StandardCharsets.UTF_8), "audio/webm", null);

        assertThat(transcript.language()).isEqualTo("fr");
        assertThat(transcript.text()).contains("[00:00] Bonjour").contains("[01:05] le monde");
        assertThat(auth.get()).isEqualTo("Bearer secret-key");
    }

    @Test
    @DisplayName("modèle Whisper : response_format=verbose_json (segments horodatés)")
    void whisperUsesVerboseJson() {
        AtomicReference<String> body = new AtomicReference<>();
        String responseBody = "{\"text\":\"salut\",\"language\":\"fr\"}";
        String baseUrl = startServerCapturingBody(200, responseBody, body);
        TranscriptionProperties on = new TranscriptionProperties(baseUrl, "secret-key", "whisper-1", null,
                Duration.ofSeconds(10), null);
        HttpTranscriptionProvider provider = new HttpTranscriptionProvider(on, RestClient.builder());

        provider.transcribe("opus".getBytes(StandardCharsets.UTF_8), "audio/webm", null);

        assertThat(body.get()).contains("name=\"model\"").contains("whisper-1");
        assertThat(body.get()).contains("verbose_json");
    }

    @Test
    @DisplayName("modèle gpt-4o-transcribe : response_format=json (pas verbose_json), model transmis")
    void gpt4oUsesPlainJson() {
        AtomicReference<String> body = new AtomicReference<>();
        String responseBody = "{\"text\":\"salut\"}";
        String baseUrl = startServerCapturingBody(200, responseBody, body);
        TranscriptionProperties on = new TranscriptionProperties(baseUrl, "secret-key", "gpt-4o-transcribe", null,
                Duration.ofSeconds(10), null);
        HttpTranscriptionProvider provider = new HttpTranscriptionProvider(on, RestClient.builder());

        Transcript transcript = provider.transcribe("opus".getBytes(StandardCharsets.UTF_8), "audio/webm", null);

        assertThat(body.get()).contains("name=\"model\"").contains("gpt-4o-transcribe");
        assertThat(body.get()).contains("name=\"response_format\"").contains("json");
        assertThat(body.get()).doesNotContain("verbose_json");
        assertThat(transcript.text()).isEqualTo("salut");
    }

    @Test
    @DisplayName("erreur du service (500) : TranscriptionProviderException")
    void serviceErrorThrows() {
        String baseUrl = startServer(new AtomicInteger(), 500, "boom");
        TranscriptionProperties on = new TranscriptionProperties(baseUrl, "k", null, null, Duration.ofSeconds(5), null);
        HttpTranscriptionProvider provider = new HttpTranscriptionProvider(on, RestClient.builder());

        assertThatThrownBy(() -> provider.transcribe(new byte[] {1}, "audio/webm", null))
                .isInstanceOf(TranscriptionProviderException.class);
    }

    private String startServer(AtomicInteger hits, int status, String body) {
        return startServer(hits, status, body, new AtomicReference<>());
    }

    /** Démarre un serveur qui capture le corps multipart brut reçu (pour vérifier les champs envoyés). */
    private String startServerCapturingBody(int status, String responseBody, AtomicReference<String> captured) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/audio/transcriptions", exchange -> {
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

    private String startServer(AtomicInteger hits, int status, String body, AtomicReference<String> auth) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/audio/transcriptions", exchange -> {
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
}
