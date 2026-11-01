package fr.claudegateway.atelier.veille;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.veille.JarvisWatchItems.Item;
import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushNotificationService;

/** F-230 / SF-230-02 (D3/D6) — un passage : base, rien de neuf, changement transmis, panne au 2ᵉ échec. */
class JarvisWatchPassageTest {

    private final UUID userId = UUID.randomUUID();
    private JarvisWatchProbeRunner probes;
    private JarvisWatchRepository watches;
    private JarvisWatchSnapshotRepository snapshots;
    private JarvisWatchRunRepository runs;
    private JarvisWatchThreadNote note;
    private PushNotificationService push;
    private JarvisWatchChangeListener listener;
    private JarvisWatchPassage passage;
    private JarvisWatch watch;
    private final Workspace subject = Workspace.builder().id(UUID.randomUUID()).build();

    private static final List<Item> ITEMS = List.of(new Item("A", "Alarme", "En cours", "PO", "d1", ""));
    private static final List<Item> MOVED = List.of(new Item("A", "Alarme", "Bloqué", "PO", "d2", ""));

    @BeforeEach
    void setUp() {
        probes = mock(JarvisWatchProbeRunner.class);
        watches = mock(JarvisWatchRepository.class);
        snapshots = mock(JarvisWatchSnapshotRepository.class);
        runs = mock(JarvisWatchRunRepository.class);
        note = mock(JarvisWatchThreadNote.class);
        push = mock(PushNotificationService.class);
        listener = mock(JarvisWatchChangeListener.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        passage = new JarvisWatchPassage(probes, watches, snapshots, runs, note, tx,
                Clock.fixed(OffsetDateTime.parse("2026-11-02T08:00:00Z").toInstant(), ZoneOffset.UTC));
        passage.setPush(push);
        passage.setListeners(List.of(listener));
        watch = JarvisWatch.builder().id(UUID.randomUUID()).userId(userId).workspaceId(subject.getId()).name("Agenor")
                .build();
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void probe(List<Item> items) {
        when(probes.run(watch, subject)).thenReturn(new JarvisWatchProbeRunner.Outcome(items, null, null, 10L));
    }

    private void snapshot(List<Item> items) {
        when(snapshots.findByWatchIdAndUserId(watch.getId(), userId)).thenReturn(Optional.of(JarvisWatchSnapshot
                .builder().userId(userId).watchId(watch.getId()).itemsJson(JarvisWatchItems.toJson(items)).build()));
    }

    @Test
    void theFirstPassageSetsTheBaselineSilently() {
        probe(ITEMS);
        when(snapshots.findByWatchIdAndUserId(watch.getId(), userId)).thenReturn(Optional.empty());
        JarvisWatchRun run = passage.run(watch, subject, null, JarvisWatchRun.SCHEDULED);
        assertThat(run.getStatus()).isEqualTo(JarvisWatchRun.BASELINE);
        verify(snapshots).save(any());
        verify(listener, never()).onChange(any(), any(), any(), any());
    }

    @Test
    void nothingMovedCostsNothing() {
        probe(ITEMS);
        snapshot(ITEMS);
        JarvisWatchRun run = passage.run(watch, subject, null, JarvisWatchRun.SCHEDULED);
        assertThat(run.getStatus()).isEqualTo(JarvisWatchRun.NO_CHANGE);
        verify(snapshots, never()).save(any());
        verify(listener, never()).onChange(any(), any(), any(), any());
        verify(note, never()).post(any(), anyString());
    }

    @Test
    void aChangeIsHandedToTheListenerAsADiffOnly() {
        probe(MOVED);
        snapshot(ITEMS);
        JarvisWatchRun run = passage.run(watch, subject, null, JarvisWatchRun.SCHEDULED);
        assertThat(run.getStatus()).isEqualTo(JarvisWatchRun.CHANGED);
        assertThat(run.getChangeCount()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JarvisWatchDiff.Change>> diff = ArgumentCaptor.forClass(List.class);
        verify(listener).onChange(eq(watch), eq(subject), eq(run), diff.capture());
        assertThat(diff.getValue()).extracting(JarvisWatchDiff.Change::kind).containsExactly(JarvisWatchDiff.Kind.STATUS);
        verify(snapshots).save(any());
    }

    @Test
    void theSecondFailureInARowBreaksTheWatchAndSaysSo() {
        when(probes.run(watch, subject)).thenReturn(new JarvisWatchProbeRunner.Outcome(List.of(),
                JarvisWatchProbeRunner.ERR_EXIT, "code 1", 10L));
        JarvisWatchRun first = passage.run(watch, subject, null, JarvisWatchRun.SCHEDULED);
        assertThat(first.getStatus()).isEqualTo(JarvisWatchRun.FAILED);
        verify(watches).recordOutcome(eq(watch.getId()), eq(userId), any(), eq(JarvisWatchRun.FAILED), eq(1), eq(false));
        verify(push, never()).notify(any(), any(), any());

        watch.setConsecutiveFailures(1);
        passage.run(watch, subject, null, JarvisWatchRun.SCHEDULED);
        verify(watches).recordOutcome(eq(watch.getId()), eq(userId), any(), eq(JarvisWatchRun.FAILED), eq(2), eq(true));
        verify(push).notify(userId, subject.getId(), PushEvent.WATCH_BROKEN);
        verify(note).post(eq(watch), org.mockito.ArgumentMatchers.contains("en panne"));

        // Déjà en panne : pas de seconde notification.
        watch.setConsecutiveFailures(2);
        watch.setBroken(true);
        passage.run(watch, subject, null, JarvisWatchRun.SCHEDULED);
        verify(push, org.mockito.Mockito.times(1)).notify(any(), any(), any());
    }

    @Test
    void aSuccessRepairsABrokenWatch() {
        watch.setBroken(true);
        watch.setConsecutiveFailures(3);
        probe(ITEMS);
        snapshot(ITEMS);
        passage.run(watch, subject, null, JarvisWatchRun.SCHEDULED);
        verify(watches).recordOutcome(eq(watch.getId()), eq(userId), any(), eq(JarvisWatchRun.NO_CHANGE), anyInt(),
                eq(false));
        verify(note).post(eq(watch), org.mockito.ArgumentMatchers.contains("réparée"));
    }
}
