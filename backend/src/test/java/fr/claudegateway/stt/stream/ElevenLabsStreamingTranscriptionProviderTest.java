package fr.claudegateway.stt.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import fr.claudegateway.jarvis.stt.JarvisSttStreamProperties;
import fr.claudegateway.speech.elevenlabs.ElevenLabsProperties;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderUnavailableException;

/** ElevenLabs Scribe v2 Realtime (F-233 / SF-233-04) : URL de session, et rien ne part sans clé. */
class ElevenLabsStreamingTranscriptionProviderTest {

    @Test
    @DisplayName("l'URL fixe modèle, PCM 16 kHz, fin de tour par VAD (bornée) et langue ; jamais de jeton")
    void sessionUrl() {
        String url = ElevenLabsStreamingTranscriptionProvider.sessionUrl("wss://api.elevenlabs.io",
                "scribe_v2_realtime", "FR", 0.7);
        assertThat(url).isEqualTo("wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime"
                + "&audio_format=pcm_16000&commit_strategy=vad&vad_silence_threshold_secs=0.70&language_code=fr");
        assertThat(ElevenLabsStreamingTranscriptionProvider.sessionUrl("wss://x", "m", null, 9))
                .endsWith("vad_silence_threshold_secs=3.00").doesNotContain("token");
    }

    @Test
    @DisplayName("sans clé : non configuré, aucun appel")
    void unconfigured() {
        ElevenLabsStreamingTranscriptionProvider provider = new ElevenLabsStreamingTranscriptionProvider(
                new ElevenLabsProperties(null, null, null, null, null, null),
                new JarvisSttStreamProperties(null, null, null, null, null, null), RestClient.builder(),
                Clock.systemUTC());
        assertThat(provider.isConfigured()).isFalse();
        assertThat(provider.name()).isEqualTo("elevenlabs");
        assertThatThrownBy(() -> provider.openStreamingSession("fr", 0.7, java.util.List.of()))
                .isInstanceOf(TranscriptionProviderUnavailableException.class);
    }

    @org.junit.jupiter.api.Test
    void f239TheVocabularyRidesAsOneKeytermParameterPerTerm() {
        String url = ElevenLabsStreamingTranscriptionProvider.sessionUrl("wss://x", "scribe_v2_realtime", "fr", 0.7,
                java.util.List.of("CAGIP", "LDIC-223", "René Martin"));
        org.assertj.core.api.Assertions.assertThat(url)
                .contains("&keyterms=CAGIP&keyterms=LDIC-223&keyterms=Ren%C3%A9+Martin");
    }
}
