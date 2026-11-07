package fr.claudegateway.speech.elevenlabs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import fr.claudegateway.speech.AlignedSpeech.AlignedChunk;
import fr.claudegateway.speech.SpeechProviderException;
import fr.claudegateway.speech.SpeechProviderUnavailableException;

/**
 * F-250 / SF-250-01 — la voix alignée ElevenLabs, sur des réponses <b>réelles enregistrées</b> le 2026-11-07 (audio
 * tronqué, séparateurs et alignements d'origine) : objets JSON séparés par une ligne vide, temps absolus.
 */
class ElevenLabsAlignedSpeechTest {

    private static final String VOICE = "onwK4e9ZLuTAKqWW03F9";
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String fixture(String name) throws Exception {
        try (InputStream in = ElevenLabsAlignedSpeechTest.class.getResourceAsStream("/elevenlabs/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Un faux fournisseur qui envoie `objects` un par un ; après le premier, il attend `gate` (au plus 3 s). */
    private ElevenLabsSpeechProvider provider(String key, int status, List<String> objects, CountDownLatch gate,
            AtomicReference<String> seen) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.set(exchange.getRequestURI() + " " + exchange.getRequestHeaders().getFirst("xi-api-key") + " " + body);
            if (status != 200) {
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            for (int i = 0; i < objects.size(); i++) {
                out.write((objects.get(i) + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (i == 0 && gate != null) {
                    try {
                        gate.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new ElevenLabsSpeechProvider(new ElevenLabsProperties(key, base, null, Duration.ofSeconds(5), null,
                null), new ObjectMapper(), RestClient.builder());
    }

    private static List<String> objects(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : raw.split("\n\n")) {
            if (!part.isBlank()) {
                out.add(part);
            }
        }
        return out;
    }

    @Test
    @DisplayName("même requête que /stream sur /stream/with-timestamps ; alignement normalisé relayé ; un seul morceau aligné")
    void relaysRecordedResponse() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        List<String> raw = objects(fixture("with-timestamps-multilingual.ndjson"));
        ElevenLabsSpeechProvider provider = provider("k-test", 200, raw, null, seen);
        List<AlignedChunk> chunks = new ArrayList<>();
        long count = provider.synthesizeAligned("Bonjour Monsieur, je vous écoute.", VOICE, "ignorées", chunks::add);
        assertThat(count).isEqualTo(raw.size()).isEqualTo(chunks.size());
        assertThat(seen.get())
                .startsWith("/v1/text-to-speech/" + VOICE + "/stream/with-timestamps?output_format=mp3_44100_128 k-test ")
                .contains("\"model_id\":\"eleven_flash_v2_5\"").contains("\"language_code\":\"fr\"");
        AlignedChunk first = chunks.get(0);
        assertThat(first.aligned()).isTrue();
        assertThat(first.audioBase64()).isNotEmpty();
        // normalized_alignment : le texte tel que prononcé, espace de tête compris.
        assertThat(String.join("", first.characters())).isEqualTo(" Bonjour Monsieur, je vous écoute. ");
        assertThat(first.starts()).hasSameSizeAs(first.characters());
        assertThat(chunks.subList(1, chunks.size())).allSatisfy(c -> {
            assertThat(c.aligned()).isFalse();
            assertThat(c.audioBase64()).isNotEmpty();
        });
    }

    @Test
    @DisplayName("réponse longue : plusieurs morceaux alignés, temps absolus croissants d'un morceau à l'autre")
    void absoluteTimesAcrossChunks() throws Exception {
        List<String> raw = objects(fixture("with-timestamps-flash-long.ndjson"));
        ElevenLabsSpeechProvider provider = provider("k", 200, raw, null, new AtomicReference<>());
        List<AlignedChunk> chunks = new ArrayList<>();
        provider.synthesizeAligned("Le point EDENRED…", VOICE, null, chunks::add);
        List<AlignedChunk> aligned = chunks.stream().filter(AlignedChunk::aligned).toList();
        assertThat(aligned).hasSizeGreaterThanOrEqualTo(3);
        double previousEnd = -1;
        for (AlignedChunk c : aligned) {
            assertThat(c.starts().get(0)).isGreaterThanOrEqualTo(previousEnd - 1e-6);
            previousEnd = c.ends().get(c.ends().size() - 1);
        }
        assertThat(previousEnd).isGreaterThan(10);
    }

    @Test
    @DisplayName("relais au fil de l'eau : le premier morceau arrive avant que le fournisseur ait fini")
    void streamsChunkByChunk() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        AtomicBoolean firstBeforeEnd = new AtomicBoolean(false);
        List<String> raw = objects(fixture("with-timestamps-multilingual.ndjson"));
        ElevenLabsSpeechProvider provider = provider("k", 200, raw, gate, new AtomicReference<>());
        List<AlignedChunk> chunks = new ArrayList<>();
        provider.synthesizeAligned("Bonjour.", VOICE, null, chunk -> {
            if (chunks.isEmpty()) {
                // Le serveur attend ce signal avant d'envoyer la suite : s'il est reçu, le relais n'a pas attendu la fin.
                firstBeforeEnd.set(gate.getCount() == 1);
                gate.countDown();
            }
            chunks.add(chunk);
        });
        assertThat(firstBeforeEnd).isTrue();
        assertThat(chunks).hasSize(raw.size());
    }

    @Test
    @DisplayName("alignement incohérent : le son passe seul ; objet vide ignoré ; erreurs nommées sans la clé")
    void malformedAndErrors() throws Exception {
        List<String> raw = List.of(
                "{\"audio_base64\":\"QUJD\",\"alignment\":{\"characters\":[\"a\",\"b\"],"
                        + "\"character_start_times_seconds\":[0.0],\"character_end_times_seconds\":[0.1,0.2]}}",
                "{\"audio_base64\":null,\"alignment\":null}",
                "{\"audio_base64\":\"REVG\"}");
        ElevenLabsSpeechProvider provider = provider("k", 200, raw, null, new AtomicReference<>());
        List<AlignedChunk> chunks = new ArrayList<>();
        assertThat(provider.synthesizeAligned("x", VOICE, null, chunks::add)).isEqualTo(2);
        assertThat(chunks).extracting(AlignedChunk::audioBase64).containsExactly("QUJD", "REVG");
        assertThat(chunks).noneMatch(AlignedChunk::aligned);
        stop();

        AtomicReference<String> seen = new AtomicReference<>();
        assertThatThrownBy(() -> provider(null, 200, raw, null, seen).synthesizeAligned("x", VOICE, null, c -> { }))
                .isInstanceOf(SpeechProviderUnavailableException.class);
        assertThat(seen.get()).isNull();
        stop();
        assertThatThrownBy(() -> provider("secret-k", 401, raw, null, seen).synthesizeAligned("x", VOICE, null, c -> { }))
                .isInstanceOf(SpeechProviderException.class).hasMessageNotContaining("secret-k");
        stop();
        assertThatThrownBy(() -> provider("k", 200, List.of(), null, seen).synthesizeAligned("x", VOICE, null, c -> { }))
                .isInstanceOf(SpeechProviderException.class);
    }
}
