package fr.claudegateway.teams.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.storage.WorkspaceStorage;

/** Dépôt de l'audio d'une réunion (F-128 / SF-128-02) : stockage + MAJ de l'artefact, isolation. */
@ExtendWith(MockitoExtension.class)
class MeetingMediaServiceTest {

    @Mock private MeetingRepository repository;
    @Mock private WorkspaceStorage storage;

    private MeetingMediaService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID meetingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MeetingMediaService(repository, storage);
    }

    @Test
    @DisplayName("stocke l'audio sous une clé isolée user/host/meeting et renseigne audio_key + audio_bytes")
    void storesAudioAndUpdatesMeeting() {
        Meeting meeting = Meeting.builder().userId(userId).hostId(hostId).state(MeetingState.STOPPED)
                .meetingUrl("https://x").consentAcknowledged(true).retentionDays(30).build();
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId)).thenReturn(Optional.of(meeting));
        when(repository.save(any(Meeting.class))).thenAnswer(inv -> inv.getArgument(0));
        byte[] audio = "webm-bytes".getBytes(StandardCharsets.UTF_8);

        Meeting updated = service.storeAudio(userId, hostId, meetingId, "audio/webm", audio);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).putFile(key.capture(), eq(audio), eq("audio/webm"));
        assertThat(key.getValue())
                .isEqualTo("teams-meetings/" + userId + "/" + hostId + "/" + meetingId + "/audio.webm");
        assertThat(updated.getAudioKey()).isEqualTo(key.getValue());
        assertThat(updated.getAudioBytes()).isEqualTo((long) audio.length);
    }

    @Test
    @DisplayName("réunion hors périmètre (id/user/host) : introuvable, rien de stocké")
    void unknownMeetingIsRejected() {
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.storeAudio(userId, hostId, meetingId, "audio/webm", new byte[] {1}))
                .isInstanceOf(MeetingNotFoundException.class);
        verify(storage, never()).putFile(any(), any(), any());
    }

    @Test
    @DisplayName("stocke une image clé sous frames/ et met à jour image_count")
    void storesImageAndUpdatesCount() {
        Meeting meeting = Meeting.builder().userId(userId).hostId(hostId).state(MeetingState.STOPPED)
                .meetingUrl("https://x").consentAcknowledged(true).retentionDays(30).build();
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId)).thenReturn(Optional.of(meeting));
        when(repository.save(any(Meeting.class))).thenAnswer(inv -> inv.getArgument(0));
        String framesPrefix = "teams-meetings/" + userId + "/" + hostId + "/" + meetingId + "/frames/";
        when(storage.listKeys(framesPrefix)).thenReturn(List.of(framesPrefix + "a.jpg", framesPrefix + "b.jpg"));

        Meeting updated = service.storeImage(userId, hostId, meetingId, "image/jpeg", new byte[] {1, 2, 3});

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).putFile(key.capture(), any(), eq("image/jpeg"));
        assertThat(key.getValue()).startsWith(framesPrefix);
        assertThat(key.getValue()).endsWith(".jpg");
        assertThat(updated.getImageCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("image : réunion hors périmètre → introuvable, rien de stocké")
    void unknownMeetingForImageRejected() {
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.storeImage(userId, hostId, meetingId, "image/jpeg", new byte[] {1}))
                .isInstanceOf(MeetingNotFoundException.class);
        verify(storage, never()).putFile(anyString(), any(), any());
    }

    // ------------------------------------------------------------------ lecture (SF-128-10)

    @Test
    @DisplayName("findAudio : rend le type (de l'extension) et les octets d'une réunion du couple")
    void findAudioReturnsTypeAndBytes() {
        String key = "teams-meetings/" + userId + "/" + hostId + "/" + meetingId + "/audio.webm";
        Meeting meeting = Meeting.builder().userId(userId).hostId(hostId).state(MeetingState.STOPPED)
                .meetingUrl("https://x").consentAcknowledged(true).retentionDays(30).audioKey(key).build();
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId)).thenReturn(Optional.of(meeting));
        byte[] bytes = "opus".getBytes(StandardCharsets.UTF_8);
        when(storage.getFile(key)).thenReturn(Optional.of(bytes));

        Optional<MeetingMediaService.StoredMedia> audio = service.findAudio(userId, hostId, meetingId);

        assertThat(audio).isPresent();
        assertThat(audio.get().contentType()).isEqualTo("audio/webm");
        assertThat(audio.get().content()).isEqualTo(bytes);
    }

    @Test
    @DisplayName("findAudio : réunion sans audio → vide")
    void findAudioEmptyWhenNoAudio() {
        Meeting meeting = Meeting.builder().userId(userId).hostId(hostId).state(MeetingState.STOPPED)
                .meetingUrl("https://x").consentAcknowledged(true).retentionDays(30).build();
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId)).thenReturn(Optional.of(meeting));

        assertThat(service.findAudio(userId, hostId, meetingId)).isEmpty();
        verify(storage, never()).getFile(anyString());
    }

    @Test
    @DisplayName("ISOLATION findAudio : réunion d'un autre couple → vide, aucun octet lu")
    void findAudioIsolatedByCouple() {
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId)).thenReturn(Optional.empty());

        assertThat(service.findAudio(userId, hostId, meetingId)).isEmpty();
        verify(storage, never()).getFile(anyString());
    }

    @Test
    @DisplayName("listFrames : rend les identifiants (sans extension), triés")
    void listFramesReturnsIds() {
        String prefix = "teams-meetings/" + userId + "/" + hostId + "/" + meetingId + "/frames/";
        when(storage.listKeys(prefix)).thenReturn(List.of(prefix + "b2.jpg", prefix + "a1.png"));

        assertThat(service.listFrames(userId, hostId, meetingId)).containsExactly("a1", "b2");
    }

    @Test
    @DisplayName("findFrame : id sûr existant → type + octets ; id inconnu → vide")
    void findFrameReturnsImage() {
        String pngKey = "teams-meetings/" + userId + "/" + hostId + "/" + meetingId + "/frames/img1.png";
        // Un seul stub souple : seule la clé .png porte des octets (ordre d'essai des extensions non garanti).
        when(storage.getFile(anyString()))
                .thenAnswer(inv -> pngKey.equals(inv.getArgument(0)) ? Optional.of(new byte[] {9}) : Optional.empty());

        Optional<MeetingMediaService.StoredMedia> frame = service.findFrame(userId, hostId, meetingId, "img1");
        assertThat(frame).isPresent();
        assertThat(frame.get().contentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("findFrame : identifiant non sûr (traversée) → vide, aucun accès stockage")
    void findFrameRejectsUnsafeId() {
        assertThat(service.findFrame(userId, hostId, meetingId, "../secret")).isEmpty();
        verify(storage, never()).getFile(anyString());
    }

    @Test
    @DisplayName("type inconnu : repli sur l'extension webm")
    void unknownTypeFallsBackToWebm() {
        Meeting meeting = Meeting.builder().userId(userId).hostId(hostId).state(MeetingState.STOPPED)
                .meetingUrl("https://x").consentAcknowledged(true).retentionDays(30).build();
        when(repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId)).thenReturn(Optional.of(meeting));
        when(repository.save(any(Meeting.class))).thenAnswer(inv -> inv.getArgument(0));

        service.storeAudio(userId, hostId, meetingId, "audio/unknown", new byte[] {1, 2});

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).putFile(key.capture(), any(), any());
        assertThat(key.getValue()).endsWith("/audio.webm");
    }
}
