package fr.claudegateway.teams.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.teams.meeting.MeetingMediaService.StoredMedia;
import fr.claudegateway.teams.meeting.stt.TranscriptionProperties;
import fr.claudegateway.teams.meeting.stt.TranscriptionProvider;
import fr.claudegateway.teams.meeting.stt.TranscriptionProvider.Transcript;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderException;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderUnavailableException;

/** La transcription (F-128 / SF-128-04) : garde d'opt-in, enfilage, worker asynchrone, isolation. */
@ExtendWith(MockitoExtension.class)
class TranscriptionServiceTest {

    @Mock private MeetingRepository repository;
    @Mock private MeetingMediaService media;
    @Mock private TranscriptionProvider provider;

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID meetingId = UUID.randomUUID();
    private final RadarScope scope = new RadarScope(userId, hostId);

    private static final TranscriptionProperties OFF =
            new TranscriptionProperties(null, null, null, null, null, null);
    private static final TranscriptionProperties ON =
            new TranscriptionProperties("http://stt.local", "key", null, "fr", null, null);

    private TranscriptionService serviceOff;
    private TranscriptionService serviceOn;

    @BeforeEach
    void setUp() {
        serviceOff = new TranscriptionService(repository, media, provider, OFF);
        serviceOn = new TranscriptionService(repository, media, provider, ON);
    }

    private Meeting meetingWithAudio() {
        return Meeting.builder().userId(userId).hostId(hostId).state(MeetingState.STOPPED)
                .meetingUrl("https://x").consentAcknowledged(true).retentionDays(30)
                .audioKey("teams-meetings/" + userId + "/" + hostId + "/" + meetingId + "/audio.webm")
                .transcriptStatus(TranscriptStatus.NONE).build();
    }

    // ---------------------------------------------------------------- requestTranscription

    @Test
    @DisplayName("STT non configuré : exception nommée, rien enfilé, AUCUN appel provider")
    void requestNotConfigured() {
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId))
                .thenReturn(Optional.of(meetingWithAudio()));

        assertThatThrownBy(() -> serviceOff.requestTranscription(scope, meetingId))
                .isInstanceOf(TranscriptionProviderUnavailableException.class);
        verify(repository, never()).save(any());
        verify(provider, never()).transcribe(any(), anyString(), any());
    }

    @Test
    @DisplayName("réunion sans audio : MeetingStateException")
    void requestWithoutAudio() {
        Meeting noAudio = meetingWithAudio();
        noAudio.setAudioKey(null);
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId)).thenReturn(Optional.of(noAudio));

        assertThatThrownBy(() -> serviceOn.requestTranscription(scope, meetingId))
                .isInstanceOf(MeetingStateException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("configuré : passe PENDING")
    void requestEnqueues() {
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId))
                .thenReturn(Optional.of(meetingWithAudio()));
        when(repository.save(any(Meeting.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = serviceOn.requestTranscription(scope, meetingId);

        assertThat(response.transcriptStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("déjà en cours (PENDING) : idempotent, pas de réenfilage ni d'appel")
    void requestIdempotentWhenInFlight() {
        Meeting inFlight = meetingWithAudio();
        inFlight.setTranscriptStatus(TranscriptStatus.PENDING);
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId)).thenReturn(Optional.of(inFlight));

        var response = serviceOn.requestTranscription(scope, meetingId);

        assertThat(response.transcriptStatus()).isEqualTo("PENDING");
        verify(repository, never()).save(any());
    }

    // ---------------------------------------------------------------- transcribePending (worker)

    @Test
    @DisplayName("worker : PENDING -> appel STT -> TRANSCRIBED avec transcript + langue")
    void transcribePendingSuccess() {
        Meeting pending = meetingWithAudio();
        pending.setTranscriptStatus(TranscriptStatus.PENDING);
        when(repository.findByTranscriptStatus(TranscriptStatus.PENDING)).thenReturn(List.of(pending));
        when(repository.save(any(Meeting.class))).thenAnswer(inv -> inv.getArgument(0));
        when(media.findAudio(userId, hostId, pending.getId()))
                .thenReturn(Optional.of(new StoredMedia("audio/webm", new byte[] {1, 2})));
        when(provider.transcribe(any(), anyString(), any()))
                .thenReturn(new Transcript("[00:00] Bonjour", "fr"));

        int processed = serviceOn.transcribePending();

        assertThat(processed).isEqualTo(1);
        assertThat(pending.getTranscriptStatus()).isEqualTo(TranscriptStatus.TRANSCRIBED);
        assertThat(pending.getTranscript()).contains("Bonjour");
        assertThat(pending.getTranscriptLang()).isEqualTo("fr");
    }

    @Test
    @DisplayName("worker : échec du service -> FAILED + message nommé, le lot continue")
    void transcribePendingFailure() {
        Meeting pending = meetingWithAudio();
        pending.setTranscriptStatus(TranscriptStatus.PENDING);
        when(repository.findByTranscriptStatus(TranscriptStatus.PENDING)).thenReturn(List.of(pending));
        when(repository.save(any(Meeting.class))).thenAnswer(inv -> inv.getArgument(0));
        when(media.findAudio(userId, hostId, pending.getId()))
                .thenReturn(Optional.of(new StoredMedia("audio/webm", new byte[] {1})));
        when(provider.transcribe(any(), anyString(), any()))
                .thenThrow(new TranscriptionProviderException("boom"));

        serviceOn.transcribePending();

        assertThat(pending.getTranscriptStatus()).isEqualTo(TranscriptStatus.FAILED);
        assertThat(pending.getTranscriptError()).isNotBlank();
    }

    @Test
    @DisplayName("worker : audio introuvable -> FAILED, aucun appel STT")
    void transcribePendingNoAudio() {
        Meeting pending = meetingWithAudio();
        pending.setTranscriptStatus(TranscriptStatus.PENDING);
        when(repository.findByTranscriptStatus(TranscriptStatus.PENDING)).thenReturn(List.of(pending));
        when(repository.save(any(Meeting.class))).thenAnswer(inv -> inv.getArgument(0));
        when(media.findAudio(userId, hostId, pending.getId())).thenReturn(Optional.empty());

        serviceOn.transcribePending();

        assertThat(pending.getTranscriptStatus()).isEqualTo(TranscriptStatus.FAILED);
        verify(provider, never()).transcribe(any(), anyString(), any());
    }
}
