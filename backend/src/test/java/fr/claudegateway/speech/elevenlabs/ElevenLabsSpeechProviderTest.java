package fr.claudegateway.speech.elevenlabs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import fr.claudegateway.speech.SpeechProviderException;
import fr.claudegateway.speech.SpeechProviderUnavailableException;

/** La voix ElevenLabs (F-233 / SF-233-06) : requête en flux, refus sans clé ni identifiant, erreur nommée. */
class ElevenLabsSpeechProviderTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ElevenLabsSpeechProvider provider(String key, int status, AtomicReference<String> seen) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.set(exchange.getRequestURI() + " " + exchange.getRequestHeaders().getFirst("xi-api-key") + " " + body);
            byte[] audio = status == 200 ? new byte[] {9, 8, 7} : new byte[0];
            exchange.sendResponseHeaders(status, audio.length == 0 ? -1 : audio.length);
            exchange.getResponseBody().write(audio);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new ElevenLabsSpeechProvider(new ElevenLabsProperties(key, base, null, Duration.ofSeconds(5), null,
                null), new ObjectMapper(), RestClient.builder());
    }

    @Test
    @DisplayName("lit en flux : voix dans le chemin, clé en en-tête, modèle Flash v2.5 et français dans le corps")
    void streams() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        ElevenLabsSpeechProvider provider = provider("k-test", 200, seen);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long written = provider.synthesizeStream("Bonjour, Monsieur.", "onwK4e9ZLuTAKqWW03F9", "ignorées", out);
        assertThat(written).isEqualTo(3);
        assertThat(out.toByteArray()).containsExactly(9, 8, 7);
        assertThat(seen.get()).startsWith("/v1/text-to-speech/onwK4e9ZLuTAKqWW03F9/stream?output_format=mp3_44100_128 k-test ")
                .contains("\"model_id\":\"eleven_flash_v2_5\"").contains("\"language_code\":\"fr\"")
                .contains("Bonjour, Monsieur.");
        assertThat(provider.synthesize("Oui ?", "onwK4e9ZLuTAKqWW03F9", null).bytes()).containsExactly(9, 8, 7);
        assertThat(provider.name()).isEqualTo("elevenlabs");
    }

    @Test
    @DisplayName("sans clé ou voix illisible : refus sans appel ; erreur du fournisseur : exception nommée")
    void refusals() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        assertThatThrownBy(() -> provider(null, 200, seen).synthesize("x", "onwK4e9ZLuTAKqWW03F9", null))
                .isInstanceOf(SpeechProviderUnavailableException.class);
        stop();
        assertThatThrownBy(() -> provider("k", 200, seen).synthesize("x", "../admin", null))
                .isInstanceOf(SpeechProviderUnavailableException.class);
        assertThat(seen.get()).isNull();
        stop();
        assertThatThrownBy(() -> provider("k", 401, seen).synthesize("x", "onwK4e9ZLuTAKqWW03F9", null))
                .isInstanceOf(SpeechProviderException.class).hasMessageNotContaining("k");
        assertThat(ElevenLabsSpeechProvider.isVoiceId("JBFqnCBsd6RMkjVDRZzb")).isTrue();
        assertThat(ElevenLabsSpeechProvider.isVoiceId("onyx")).isFalse();
    }
}
