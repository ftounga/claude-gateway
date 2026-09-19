package fr.claudegateway.teams.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.storage.WorkspaceStorageDeletionException;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.teams.meeting.dto.MeetingResponse;

/** Rétention & purge des médias de réunion (F-128 / SF-128-07) : cutoff, idempotence, isolation, best-effort. */
@ExtendWith(MockitoExtension.class)
class MeetingRetentionServiceTest {

    @Mock private MeetingRepository repository;
    @Mock private MeetingMediaService media;

    private MeetingRetentionService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MeetingRetentionService(repository, media);
    }

    private Meeting mediaBearing(OffsetDateTime startedAt, int retentionDays) {
        return Meeting.builder().id(UUID.randomUUID()).userId(userId).hostId(hostId)
                .state(MeetingState.STOPPED).meetingUrl("https://x").consentAcknowledged(true)
                .retentionDays(retentionDays).title("Comité").startedAt(startedAt)
                .audioKey("teams-meetings/k/audio.webm").audioBytes(1234L).imageCount(3)
                .transcript("[00:00] Bonjour").transcriptStatus(TranscriptStatus.TRANSCRIBED)
                .build();
    }

    @Test
    @DisplayName("purge une réunion au-delà de la rétention : médias effacés, pointeurs vidés, transcript gardé")
    void purgesExpired() {
        OffsetDateTime now = OffsetDateTime.now();
        Meeting expired = mediaBearing(now.minusDays(31), 30);
        when(repository.findMediaBearing(any())).thenReturn(List.of(expired));

        int purged = service.purgeExpired(now);

        assertThat(purged).isEqualTo(1);
        verify(media).deleteMedia(userId, hostId, expired.getId());
        verify(repository).save(expired);
        assertThat(expired.getAudioKey()).isNull();
        assertThat(expired.getAudioBytes()).isNull();
        assertThat(expired.getImageCount()).isZero();
        assertThat(expired.getMediaPurgedAt()).isEqualTo(now);
        assertThat(expired.getTranscript()).isEqualTo("[00:00] Bonjour"); // gardé
    }

    @Test
    @DisplayName("ne purge pas une réunion dans la fenêtre de rétention")
    void keepsWithinRetention() {
        OffsetDateTime now = OffsetDateTime.now();
        Meeting recent = mediaBearing(now.minusDays(5), 30);
        when(repository.findMediaBearing(any())).thenReturn(List.of(recent));

        int purged = service.purgeExpired(now);

        assertThat(purged).isZero();
        verify(media, never()).deleteMedia(any(), any(), any());
        verify(repository, never()).save(any());
        assertThat(recent.getMediaPurgedAt()).isNull();
    }

    @Test
    @DisplayName("idempotence : aucune candidate (déjà purgées exclues) -> rien à faire")
    void idempotentWhenNoCandidate() {
        when(repository.findMediaBearing(any())).thenReturn(List.of());

        assertThat(service.purgeExpired(OffsetDateTime.now())).isZero();
        verify(media, never()).deleteMedia(any(), any(), any());
    }

    @Test
    @DisplayName("best-effort : un échec d'effacement est isolé, le cycle continue")
    void failureIsolated() {
        OffsetDateTime now = OffsetDateTime.now();
        Meeting failing = mediaBearing(now.minusDays(40), 30);
        Meeting ok = mediaBearing(now.minusDays(40), 30);
        when(repository.findMediaBearing(any())).thenReturn(List.of(failing, ok));
        doThrow(new WorkspaceStorageDeletionException(1, 2, new RuntimeException()))
                .when(media).deleteMedia(userId, hostId, failing.getId());

        int purged = service.purgeExpired(now);

        assertThat(purged).isEqualTo(1);
        assertThat(failing.getMediaPurgedAt()).isNull();   // non marquée purgée -> retentée
        assertThat(failing.getAudioKey()).isNotNull();
        verify(repository, times(1)).save(ok);
        verify(repository, never()).save(failing);
        assertThat(ok.getMediaPurgedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("purge manuelle : efface et rend l'artefact à jour")
    void manualPurge() {
        Meeting meeting = mediaBearing(OffsetDateTime.now().minusDays(1), 30);
        when(repository.findByIdAndUserIdAndHostId(meeting.getId(), userId, hostId))
                .thenReturn(Optional.of(meeting));

        MeetingResponse response = service.purgeMedia(new RadarScope(userId, hostId), meeting.getId());

        verify(media).deleteMedia(userId, hostId, meeting.getId());
        verify(repository).save(meeting);
        assertThat(response.hasAudio()).isFalse();
        assertThat(response.imageCount()).isZero();
        assertThat(response.mediaPurgedAt()).isNotNull();
    }

    @Test
    @DisplayName("ISOLATION : purge manuelle d'une réunion d'un autre couple -> 404, aucun effacement")
    void manualPurgeIsolation() {
        UUID unknown = UUID.randomUUID();
        when(repository.findByIdAndUserIdAndHostId(eq(unknown), eq(userId), eq(hostId)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.purgeMedia(new RadarScope(userId, hostId), unknown))
                .isInstanceOf(MeetingNotFoundException.class);
        verify(media, never()).deleteMedia(any(), any(), any());
    }
}
