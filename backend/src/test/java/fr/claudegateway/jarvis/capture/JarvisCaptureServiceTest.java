package fr.claudegateway.jarvis.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.storage.InMemoryWorkspaceStorage;
import fr.claudegateway.jarvis.preferences.JarvisPreferenceService;

/** La capture réelle (F-239 / SF-239-03) : porte ADMIN + interrupteur, borne de 200, purge à 7 jours. */
class JarvisCaptureServiceTest {

    private final UUID alice = UUID.randomUUID();
    private final List<JarvisAudioCapture> rows = new ArrayList<>();
    private JarvisAudioCaptureRepository repository;
    private InMemoryWorkspaceStorage storage;
    private JarvisPreferenceService preferences;
    private Instant now = Instant.parse("2026-11-02T09:00:00Z");

    @BeforeEach
    void setUp() {
        repository = mock(JarvisAudioCaptureRepository.class);
        when(repository.save(any())).thenAnswer(i -> {
            rows.add(i.getArgument(0));
            return i.getArgument(0);
        });
        org.mockito.Mockito.doAnswer(i -> rows.remove((JarvisAudioCapture) i.getArgument(0))).when(repository)
                .delete(any());
        when(repository.findByUserIdOrderByCreatedAtDesc(any())).thenAnswer(i -> rows.stream()
                .filter(r -> r.getUserId().equals(i.getArgument(0)))
                .sorted(Comparator.comparing(JarvisAudioCapture::getCreatedAt).reversed()).toList());
        when(repository.findByCreatedAtBefore(any())).thenAnswer(i -> rows.stream()
                .filter(r -> r.getCreatedAt().isBefore(i.getArgument(0))).toList());
        storage = new InMemoryWorkspaceStorage();
        preferences = mock(JarvisPreferenceService.class);
        when(preferences.captureAudio(alice)).thenReturn(true);
    }

    private JarvisCaptureService service() {
        return new JarvisCaptureService(repository, storage, preferences, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void keptOnlyForAnAdminWhoSwitchedItOn() {
        assertThat(service().record(alice, false, new byte[] { 1 }, null)).isFalse();
        when(preferences.captureAudio(alice)).thenReturn(false);
        assertThat(service().record(alice, true, new byte[] { 1 }, null)).isFalse();
        assertThat(rows).isEmpty();

        when(preferences.captureAudio(alice)).thenReturn(true);
        assertThat(service().record(alice, true, new byte[] { 1, 2 }, new JarvisCaptureService.Incoming(2.4,
                "conv_stream", "elevenlabs-batch", "x".repeat(3_000), "Où en est CAGIP ?"))).isTrue();
        JarvisAudioCapture kept = rows.get(0);
        assertThat(kept.getStorageKey()).startsWith("atelier/" + alice + "/jarvis-captures/");
        assertThat(storage.getFile(kept.getStorageKey()).orElseThrow()).isEqualTo(new byte[] { 1, 2 });
        assertThat(kept.getListenMode()).isEqualTo("CONV_STREAM");
        assertThat(kept.getSeconds()).isEqualTo(2);
        assertThat(kept.getStreamText()).hasSize(2_000);
    }

    @Test
    void neverMoreThanTwoHundredPerAccount() {
        for (int i = 0; i < 205; i++) {
            now = now.plusSeconds(1);
            service().record(alice, true, new byte[] { 1 }, null);
        }
        assertThat(rows).hasSize(200);
        assertThat(storage.listKeys("atelier/" + alice + "/jarvis-captures/")).hasSize(200);
    }

    @Test
    void capturesOlderThanSevenDaysAreErased() {
        service().record(alice, true, new byte[] { 1 }, null);
        now = now.plus(java.time.Duration.ofDays(8));
        service().record(alice, true, new byte[] { 2 }, null);

        service().purgeExpired();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getCreatedAt()).isEqualTo(OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        assertThat(service().audio(alice, rows.get(0).getId())).isEmpty(); // le mock ne sert pas findByIdAndUserId
        when(repository.findByIdAndUserId(rows.get(0).getId(), alice)).thenReturn(Optional.of(rows.get(0)));
        assertThat(service().audio(alice, rows.get(0).getId()).orElseThrow()).isEqualTo(new byte[] { 2 });
    }

    @Test
    void f239TheCorrectionFillsTheLatestCaptureWithinTwoMinutesOnly() {
        service().record(alice, true, new byte[] { 1 }, new JarvisCaptureService.Incoming(1.0, "STREAM", null,
                "Où en est Cagibi", "Où en est Cagibi ?"));
        now = now.plusSeconds(60);
        assertThat(service().correctLatest(alice, "Où en est CAGIP ?")).isTrue();
        assertThat(rows.get(0).getCorrectedText()).isEqualTo("Où en est CAGIP ?");

        now = now.plusSeconds(180);
        assertThat(service().correctLatest(alice, "trop tard")).isFalse();
        assertThat(rows.get(0).getCorrectedText()).isEqualTo("Où en est CAGIP ?");
        assertThat(service().correctLatest(UUID.randomUUID(), "autrui")).isFalse();
    }
}
