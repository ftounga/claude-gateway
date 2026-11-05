package fr.claudegateway.atelier.veille;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.veille.JarvisWatchItems.Item;

/** F-247 / SF-247-07 — un passage de relevé : faits rangés, silence total ; cède la place ⇒ YIELDED, créneau rendu. */
class JarvisWatchReleveTest {

    private final UUID userId = UUID.randomUUID();
    private JarvisWatchProbeRunner probes;
    private JarvisWatchRepository watches;
    private JarvisWatchSnapshotRepository snapshots;
    private JarvisWatchRunRepository runs;
    private JarvisWatchThreadNote note;
    private JarvisWatchChangeListener listener;
    private JarvisWatchStateHook hook;
    private JarvisWatchPassage passage;
    private JarvisWatch watch;
    private final Workspace subject = Workspace.builder().id(UUID.randomUUID()).hostId(UUID.randomUUID()).build();
    private final OffsetDateTime slot = OffsetDateTime.parse("2026-11-02T08:00:00Z");

    @BeforeEach
    void setUp() {
        probes = mock(JarvisWatchProbeRunner.class);
        watches = mock(JarvisWatchRepository.class);
        snapshots = mock(JarvisWatchSnapshotRepository.class);
        runs = mock(JarvisWatchRunRepository.class);
        note = mock(JarvisWatchThreadNote.class);
        listener = mock(JarvisWatchChangeListener.class);
        hook = mock(JarvisWatchStateHook.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        passage = new JarvisWatchPassage(probes, watches, snapshots, runs, note, tx,
                Clock.fixed(slot.toInstant(), ZoneOffset.UTC));
        passage.setListeners(List.of(listener));
        passage.setStateHook(hook);
        watch = JarvisWatch.builder().id(UUID.randomUUID()).userId(userId).workspaceId(subject.getId())
                .purpose(JarvisWatch.PURPOSE_STATE).broken(true).build();
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void aReleveRecordsFactsAndNeverSpeaks() {
        List<Item> before = List.of(new Item("A", "Alarme", "En cours", "PO", "d1", ""));
        List<Item> after = List.of(new Item("A", "Alarme", "Bloqué", "PO", "d2", ""));
        when(probes.run(watch, subject)).thenReturn(new JarvisWatchProbeRunner.Outcome(after, null, null, 10L));
        when(snapshots.findByWatchIdAndUserId(watch.getId(), userId)).thenReturn(Optional.of(JarvisWatchSnapshot
                .builder().userId(userId).watchId(watch.getId()).itemsJson(JarvisWatchItems.toJson(before)).build()));

        JarvisWatchRun run = passage.run(watch, subject, slot, JarvisWatchRun.SCHEDULED);

        assertThat(run.getStatus()).isEqualTo(JarvisWatchRun.CHANGED);
        verify(hook).record(eq(watch), eq(subject), eq(after), any());
        verify(listener, never()).onChange(any(), any(), any(), any());
        verify(note, never()).post(any(), anyString());
    }

    @Test
    void preemptedIsYieldedNotAFailureAndTheSlotIsGivenBack() {
        when(probes.run(watch, subject)).thenReturn(new JarvisWatchProbeRunner.Outcome(List.of(),
                JarvisWatchProbeRunner.ERR_YIELDED + ":preempted", "remis", 10L));

        JarvisWatchRun run = passage.run(watch, subject, slot, JarvisWatchRun.SCHEDULED);

        assertThat(run.getStatus()).isEqualTo(JarvisWatchRun.YIELDED);
        assertThat(run.getErrorCode()).isEqualTo("preempted");
        verify(watches).releaseSlot(watch.getId(), slot);
        verify(watches, never()).recordOutcome(any(), any(), any(), any(), anyInt(), anyBoolean());
        verify(hook, never()).record(any(), any(), any(), any());
    }
}
