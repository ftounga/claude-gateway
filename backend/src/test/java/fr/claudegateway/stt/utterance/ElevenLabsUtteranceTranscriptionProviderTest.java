package fr.claudegateway.stt.utterance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import fr.claudegateway.jarvis.stt.JarvisUtteranceProperties;
import fr.claudegateway.speech.elevenlabs.ElevenLabsProperties;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderUnavailableException;

/** Scribe v2 (batch) derrière {@link UtteranceTranscriptionProvider} (F-239 / SF-239-04). */
class ElevenLabsUtteranceTranscriptionProviderTest {

    @Test
    void theCallCarriesTheModelTheLanguageAndOneKeytermPerTerm() {
        MultiValueMap<String, Object> form = ElevenLabsUtteranceTranscriptionProvider.form(new byte[] { 1, 2 },
                "audio/wav", "FR", List.of("Jarvis", "CAGIP", "LDIC-223"), "scribe_v2");

        assertThat(form.getFirst("model_id")).isEqualTo("scribe_v2");
        assertThat(form.getFirst("language_code")).isEqualTo("fr");
        assertThat(form.get("keyterms")).containsExactly("Jarvis", "CAGIP", "LDIC-223");
        assertThat(((ByteArrayResource) form.getFirst("file")).getFilename()).isEqualTo("enonce.wav");
        assertThat(((ByteArrayResource) ElevenLabsUtteranceTranscriptionProvider.form(new byte[] { 1 }, "audio/webm",
                null, null, "scribe_v2").getFirst("file")).getFilename()).isEqualTo("enonce.webm");
    }

    @Test
    void withoutAKeyNothingLeaves() {
        ElevenLabsProperties noKey = new ElevenLabsProperties("", null, null, Duration.ofSeconds(5), null, null);
        ElevenLabsUtteranceTranscriptionProvider provider = new ElevenLabsUtteranceTranscriptionProvider(noKey,
                new JarvisUtteranceProperties(null, null, null, null, null, null), RestClient.builder());

        assertThat(provider.isConfigured()).isFalse();
        assertThatThrownBy(() -> provider.transcribe(new byte[] { 1 }, "audio/wav", "fr", List.of()))
                .isInstanceOf(TranscriptionProviderUnavailableException.class);
    }
}
