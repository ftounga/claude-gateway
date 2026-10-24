package fr.claudegateway.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import fr.claudegateway.teams.meeting.stt.HttpTranscriptionProvider;
import fr.claudegateway.teams.meeting.stt.TranscriptionProperties;
import fr.claudegateway.teams.meeting.stt.TranscriptionProvider.WordTranscript;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderUnavailableException;

/**
 * Synthèse vocale OpenAI et transcription en mode mots (F-215 / SF-215-01), contre un petit serveur HTTP
 * local — jamais de vrai réseau.
 */
class OpenAiSpeechProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    static SpeechProperties props(String baseUrl, String key, Boolean enabled) {
        return new SpeechProperties(enabled, baseUrl, key, null, Duration.ofSeconds(10), null, null, null, null,
                null, null, null);
    }

    @Test
    @DisplayName("sans clé : exception nommée, AUCUN appel réseau")
    void notConfiguredDoesNotCall() {
        AtomicInteger hits = new AtomicInteger();
        String base = start("/audio/speech", 200, new byte[] {1}, hits, new AtomicReference<>(), new AtomicReference<>());
        OpenAiSpeechProvider provider = new OpenAiSpeechProvider(props(base, null, true), RestClient.builder(), MAPPER);

        assertThatThrownBy(() -> provider.synthesize("Bonjour", "onyx", ""))
                .isInstanceOf(SpeechProviderUnavailableException.class);
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("coupe-circuit (enabled=false) : exception nommée, AUCUN appel même avec une clé")
    void killSwitchDoesNotCall() {
        AtomicInteger hits = new AtomicInteger();
        String base = start("/audio/speech", 200, new byte[] {1}, hits, new AtomicReference<>(), new AtomicReference<>());
        OpenAiSpeechProvider provider = new OpenAiSpeechProvider(props(base, "sk", false), RestClient.builder(), MAPPER);

        assertThatThrownBy(() -> provider.synthesize("Bonjour", "onyx", ""))
                .isInstanceOf(SpeechProviderUnavailableException.class);
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("configuré : POST /audio/speech, corps JSON (modèle, voix, consignes, mp3), Bearer, octets rendus")
    void synthesizes() {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        String base = start("/audio/speech", 200, new byte[] {9, 8, 7}, new AtomicInteger(), body, auth);
        OpenAiSpeechProvider provider = new OpenAiSpeechProvider(props(base, "sk-secret", true), RestClient.builder(), MAPPER);

        SpeechProvider.SpeechAudio audio = provider.synthesize("Le VPC", "onyx", "voix posée");

        assertThat(audio.bytes()).containsExactly(9, 8, 7);
        assertThat(audio.contentType()).isEqualTo("audio/mpeg");
        assertThat(auth.get()).isEqualTo("Bearer sk-secret");
        assertThat(body.get()).contains("\"model\":\"gpt-4o-mini-tts\"").contains("\"input\":\"Le VPC\"")
                .contains("\"voice\":\"onyx\"").contains("\"instructions\":\"voix posée\"")
                .contains("\"response_format\":\"mp3\"");
    }

    @Test
    @DisplayName("fournisseur en erreur (500) : SpeechProviderException")
    void providerError() {
        String base = start("/audio/speech", 500, "boom".getBytes(StandardCharsets.UTF_8), new AtomicInteger(),
                new AtomicReference<>(), new AtomicReference<>());
        OpenAiSpeechProvider provider = new OpenAiSpeechProvider(props(base, "sk", true), RestClient.builder(), MAPPER);

        assertThatThrownBy(() -> provider.synthesize("x", "onyx", ""))
                .isInstanceOf(SpeechProviderException.class);
    }

    @Test
    @DisplayName("STT en mode mots : whisper-1, verbose_json, timestamp_granularities[]=word ; mots relus")
    void transcribesWords() {
        AtomicReference<String> body = new AtomicReference<>();
        String json = "{\"text\":\"Le VPC\",\"language\":\"french\",\"duration\":1.5,"
                + "\"words\":[{\"word\":\" Le\",\"start\":0.0,\"end\":0.3},{\"word\":\"\",\"start\":0.3,\"end\":0.3},"
                + "{\"word\":\"VPC\",\"start\":0.35,\"end\":0.2}]}";
        String base = start("/audio/transcriptions", 200, json.getBytes(StandardCharsets.UTF_8), new AtomicInteger(),
                body, new AtomicReference<>());
        HttpTranscriptionProvider stt = new HttpTranscriptionProvider(
                new TranscriptionProperties(base, "sk", "gpt-4o-transcribe", null, Duration.ofSeconds(10), null),
                RestClient.builder());

        WordTranscript words = stt.transcribeWords(new byte[] {1, 2, 3}, "audio/mpeg", null);

        assertThat(body.get()).contains("whisper-1").contains("verbose_json").contains("timestamp_granularities[]")
                .contains("word").doesNotContain("gpt-4o-transcribe");
        assertThat(words.durationSeconds()).isEqualTo(1.5);
        assertThat(words.words()).hasSize(2);
        assertThat(words.words().get(0).word()).isEqualTo("Le");
        // Fin antérieure au début : bornée au début.
        assertThat(words.words().get(1).end()).isEqualTo(0.35);
        assertThat(List.of(words.language())).containsExactly("french");
    }

    @Test
    @DisplayName("STT non configuré : mode mots refusé sans appel")
    void wordsNotConfigured() {
        HttpTranscriptionProvider stt = new HttpTranscriptionProvider(
                new TranscriptionProperties("", "", null, null, null, null), RestClient.builder());

        assertThatThrownBy(() -> stt.transcribeWords(new byte[] {1}, "audio/mpeg", null))
                .isInstanceOf(TranscriptionProviderUnavailableException.class);
    }

    private String start(String path, int status, byte[] response, AtomicInteger hits, AtomicReference<String> body,
            AtomicReference<String> auth) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(path, exchange -> {
                hits.incrementAndGet();
                auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.getResponseHeaders().add("Content-Type",
                        path.endsWith("speech") ? "audio/mpeg" : "application/json");
                exchange.sendResponseHeaders(status, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            });
            server.start();
            return "http://127.0.0.1:" + server.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException("Serveur HTTP de test non démarré", e);
        }
    }
}
