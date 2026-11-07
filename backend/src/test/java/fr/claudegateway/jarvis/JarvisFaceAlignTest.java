package fr.claudegateway.jarvis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.speech.SpeechProperties;
import fr.claudegateway.speech.SpeechProvider;
import fr.claudegateway.speech.SpeechProviderUnavailableException;
import fr.claudegateway.speech.SpeechUsage;
import fr.claudegateway.speech.elevenlabs.ElevenLabsProperties;
import fr.claudegateway.speech.elevenlabs.ElevenLabsSpeechProvider;

/** F-250 / SF-250-01 — qui aligne : coupe-circuits P5, voix capable d'aligner, voix appelable. */
class JarvisFaceAlignTest {

    private final UUID alice = UUID.randomUUID();
    private JarvisUsageService usage;
    private ElevenLabsSpeechProvider elevenLabs;

    @BeforeEach
    void setUp() {
        usage = mock(JarvisUsageService.class);
        when(usage.monthSpeechCostUsd(any())).thenReturn(BigDecimal.ZERO);
        elevenLabs = mock(ElevenLabsSpeechProvider.class);
        when(elevenLabs.isConfigured()).thenReturn(true);
    }

    private JarvisVoiceService service(String provider, String voice, JarvisFaceProperties face) {
        SpeechProperties speech = new SpeechProperties(true, "http://localhost", "k", null, Duration.ofSeconds(5),
                null, null, null, null, null, null, null);
        JarvisVoiceService service = new JarvisVoiceService(mock(SpeechProvider.class), speech,
                new JarvisVoiceProperties(voice, null, null, null, null, provider), mock(SpeechUsage.class), usage,
                mock(WorkspaceRepository.class));
        service.setElevenLabs(elevenLabs, new ElevenLabsProperties("k", null, null, null, null, null));
        service.setFace(face);
        return service;
    }

    @Test
    @DisplayName("défauts : visage et alignement allumés ; ElevenLabs aligne, OpenAI non")
    void defaults() {
        JarvisFaceProperties face = new JarvisFaceProperties(null, null);
        assertThat(face.enabled()).isTrue();
        assertThat(face.alignEnabled()).isTrue();
        assertThat(service("elevenlabs", "onwK4e9ZLuTAKqWW03F9", face).aligns(alice)).isTrue();
        assertThat(service("openai", "onyx", face).aligns(alice)).isFalse();
        assertThat(service("openai", "onyx", face).face(alice))
                .isEqualTo(new JarvisVoiceService.FaceStatus(true, false));
    }

    @Test
    @DisplayName("coupe-circuits P5 : alignement éteint ou visage éteint ⇒ pas d'alignement ; voix non configurée ⇒ non")
    void circuitBreakers() {
        assertThat(service("elevenlabs", "onwK4e9ZLuTAKqWW03F9", new JarvisFaceProperties(true, false)).aligns(alice))
                .isFalse();
        JarvisVoiceService off = service("elevenlabs", "onwK4e9ZLuTAKqWW03F9", new JarvisFaceProperties(false, true));
        assertThat(off.aligns(alice)).isFalse();
        assertThat(off.face(alice)).isEqualTo(new JarvisVoiceService.FaceStatus(false, false));
        when(elevenLabs.isConfigured()).thenReturn(false);
        assertThat(service("elevenlabs", "onwK4e9ZLuTAKqWW03F9", new JarvisFaceProperties(null, null)).aligns(alice))
                .isFalse();
    }

    @Test
    @DisplayName("lecture alignée demandée à une voix qui ne sait pas aligner : refus nommé, rien de compté")
    void refusesNonAligningVoice() {
        JarvisVoiceService service = service("openai", "onyx", new JarvisFaceProperties(null, null));
        JarvisVoiceService.Reading reading = new JarvisVoiceService.Reading(alice, null, null, "Oui.");
        assertThatThrownBy(() -> service.speakAligned(reading, chunk -> { }))
                .isInstanceOf(SpeechProviderUnavailableException.class);
        org.mockito.Mockito.verify(usage, org.mockito.Mockito.never()).addSpeech(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), any());
    }
}
