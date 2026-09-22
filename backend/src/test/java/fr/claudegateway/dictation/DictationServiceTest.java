package fr.claudegateway.dictation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.teams.meeting.stt.TranscriptionProperties;
import fr.claudegateway.teams.meeting.stt.TranscriptionProvider;

/**
 * Dicter sa demande (F-145 / SF-145-01).
 *
 * <p>Ce que ce test protège : la route <b>n'est pas un tunnel</b> vers un service tiers. Elle
 * n'accepte qu'une liste fermée de formats, écarte ce qui n'est pas de la parole <b>sans appeler
 * personne</b>, et ne garde rien.</p>
 */
class DictationServiceTest {

    private final TranscriptionProvider provider = mock(TranscriptionProvider.class);

    private DictationService service(String language) {
        return new DictationService(provider,
                new TranscriptionProperties("https://stt.example", "clé", "whisper-1", null, null, null),
                language);
    }

    private byte[] audio() {
        return new byte[DictationService.MIN_AUDIO_BYTES + 1];
    }

    @Test
    @DisplayName("LE CRITÈRE : la voix devient du texte")
    void speechBecomesText() {
        when(provider.transcribe(any(), eq("audio/webm"), any()))
                .thenReturn(new TranscriptionProvider.Transcript("  vérifie le certificat du bastion  ", "fr"));

        assertThat(service("fr").transcribe(audio(), "audio/webm;codecs=opus"))
                .isEqualTo("vérifie le certificat du bastion");
    }

    @Test
    @DisplayName("le paramètre de codec ne change pas le type : « audio/webm;codecs=opus » est accepté")
    void thecodecParameterIsNotPartOfTheType() {
        when(provider.transcribe(any(), any(), any()))
                .thenReturn(new TranscriptionProvider.Transcript("ok", "fr"));

        service(null).transcribe(audio(), "audio/webm;codecs=opus");

        verify(provider).transcribe(any(), eq("audio/webm"), any());
    }

    @Test
    @DisplayName("un extrait trop court est ignoré SANS appeler personne")
    void atooShortClipCallsNobody() {
        // Un clic sur le bouton, pas une dictée : l'envoyer coûterait un appel pour rien.
        assertThat(service(null).transcribe(new byte[10], "audio/webm")).isEmpty();
        assertThat(service(null).transcribe(null, "audio/webm")).isEmpty();

        verify(provider, never()).transcribe(any(), any(), any());
    }

    @Test
    @DisplayName("un format non audio est REFUSÉ, et rien ne part")
    void anonAudioFormatIsRefused() {
        // Sans liste fermée, cette route deviendrait un tunnel vers un service tiers.
        assertThatThrownBy(() -> service(null).transcribe(audio(), "application/zip"))
                .isInstanceOf(UnsupportedDictationAudioException.class);
        assertThatThrownBy(() -> service(null).transcribe(audio(), null))
                .isInstanceOf(UnsupportedDictationAudioException.class);

        verify(provider, never()).transcribe(any(), any(), any());
    }

    @Test
    @DisplayName("la langue configurée est transmise, l'absence de langue aussi")
    void thelanguageHintTravels() {
        when(provider.transcribe(any(), any(), any()))
                .thenReturn(new TranscriptionProvider.Transcript("ok", "fr"));

        service("fr").transcribe(audio(), "audio/webm");
        verify(provider).transcribe(any(), any(), eq("fr"));

        service("  ").transcribe(audio(), "audio/webm");
        // Vide ⇒ null : on laisse le service détecter plutôt que de lui imposer une langue fausse.
        verify(provider).transcribe(any(), any(), eq(null));
    }

    @Test
    @DisplayName("un transcript vide ou nul rend une chaîne vide, jamais une erreur")
    void anemptyTranscriptIsNotAnError() {
        when(provider.transcribe(any(), any(), any())).thenReturn(null);
        assertThat(service(null).transcribe(audio(), "audio/webm")).isEmpty();

        when(provider.transcribe(any(), any(), any()))
                .thenReturn(new TranscriptionProvider.Transcript(null, "fr"));
        assertThat(service(null).transcribe(audio(), "audio/webm")).isEmpty();
    }

    @Test
    @DisplayName("sans clé, la dictée se déclare indisponible — et rien ne part")
    void withoutAkeyTheDictationIsUnavailable() {
        DictationService unconfigured = new DictationService(provider,
                new TranscriptionProperties(null, null, null, null, null, null), null);

        assertThat(unconfigured.isAvailable()).isFalse();
        assertThat(service(null).isAvailable()).isTrue();
    }
}
