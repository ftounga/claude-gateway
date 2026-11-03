package fr.claudegateway.jarvis.stt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.jarvis.JarvisVoiceBudgetException;
import fr.claudegateway.jarvis.JarvisVoiceService;
import fr.claudegateway.stt.utterance.UtteranceTranscriptionProvider;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderUnavailableException;

/** La retranscription de l'énoncé (F-239 / SF-239-04) : vocabulaire, plafond, comptage en €. */
class JarvisUtteranceServiceTest {

    private final UUID alice = UUID.randomUUID();
    private UtteranceTranscriptionProvider provider;
    private JarvisVocabulary vocabulary;
    private JarvisVoiceService voice;
    private JarvisUsageService usage;

    @BeforeEach
    void setUp() {
        provider = mock(UtteranceTranscriptionProvider.class);
        when(provider.name()).thenReturn("elevenlabs");
        when(provider.isConfigured()).thenReturn(true);
        vocabulary = mock(JarvisVocabulary.class);
        when(vocabulary.of(alice)).thenReturn(List.of("Jarvis", "CAGIP"));
        voice = mock(JarvisVoiceService.class);
        when(voice.status(alice)).thenReturn(new JarvisVoiceService.VoiceStatus(true, "v", BigDecimal.TEN,
                BigDecimal.ONE, false, "elevenlabs"));
        usage = mock(JarvisUsageService.class);
    }

    private JarvisUtteranceService service(String providerName) {
        return new JarvisUtteranceService(List.of(provider),
                new JarvisUtteranceProperties(providerName, null, null, null, null, null), vocabulary, voice, usage);
    }

    @Test
    void transcribesWithTheAccountsVocabularyAndCountsTheSecondsInDollars() {
        when(provider.transcribe(any(), eq("audio/wav"), eq("fr"), eq(List.of("Jarvis", "CAGIP"))))
                .thenReturn(" Où en est CAGIP ? ");

        JarvisUtteranceService.Result result = service(null).transcribe(alice, new byte[64_000], "audio/wav", null);

        assertThat(result.text()).isEqualTo("Où en est CAGIP ?");
        assertThat(result.provider()).isEqualTo("elevenlabs-batch");
        // 64 000 octets de PCM 16 kHz = 2 s ; 0,27 $/h ⇒ 0,000150 $.
        // F-244 / SF-244-01 (D3) : la retranscription a ses propres colonnes, séparées du flux.
        verify(usage).addUtterance(alice, 2L, new BigDecimal("0.000150"));
    }

    @Test
    void theBudgetReachedSendsNothing() {
        when(voice.status(alice)).thenReturn(new JarvisVoiceService.VoiceStatus(true, "v", BigDecimal.TEN,
                BigDecimal.TEN, true, "elevenlabs"));

        assertThatThrownBy(() -> service(null).transcribe(alice, new byte[10], "audio/wav", 1.0))
                .isInstanceOf(JarvisVoiceBudgetException.class);
        verify(provider, never()).transcribe(any(), any(), any(), any());
        verify(usage, never()).addStream(any(), anyLong(), any());
    }

    @Test
    void switchedOffOrUnconfiguredSendsNothing() {
        assertThatThrownBy(() -> service("none").transcribe(alice, new byte[10], "audio/wav", 1.0))
                .isInstanceOf(TranscriptionProviderUnavailableException.class);
        when(provider.isConfigured()).thenReturn(false);
        assertThatThrownBy(() -> service(null).transcribe(alice, new byte[10], "audio/wav", 1.0))
                .isInstanceOf(TranscriptionProviderUnavailableException.class);
        verifyNoInteractions(usage);
    }

    @Test
    void billedSecondsAreDeclaredOrEstimatedAndBounded() {
        assertThat(JarvisUtteranceService.billedSeconds(10, "audio/wav", 2.2)).isEqualTo(3L);
        assertThat(JarvisUtteranceService.billedSeconds(10, "audio/wav", 999.0)).isEqualTo(120L);
        assertThat(JarvisUtteranceService.billedSeconds(96_000, "audio/wav", null)).isEqualTo(3L);
        assertThat(JarvisUtteranceService.billedSeconds(8_000, "audio/webm", null)).isEqualTo(2L);
        assertThat(JarvisUtteranceService.billedSeconds(1, null, null)).isEqualTo(1L);
    }
}
