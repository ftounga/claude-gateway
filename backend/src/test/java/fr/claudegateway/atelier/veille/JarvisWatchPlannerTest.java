package fr.claudegateway.atelier.veille;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.RunnerLiveness;

/** F-230 / SF-230-02 (D6/D7) — le planificateur : créneau dû, jours ouvrés, verrou, hors ligne et rattrapage. */
class JarvisWatchPlannerTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private JarvisWatchRepository watches;
    private WorkspaceRepository workspaces;
    private RunnerLiveness liveness;
    private JarvisWatchPassage passage;
    private JarvisWatchRunRepository runs;
    private JarvisWatchPlanner planner;
    private JarvisWatch watch;
    private Workspace subject;

    @BeforeEach
    void setUp() {
        watches = mock(JarvisWatchRepository.class);
        workspaces = mock(WorkspaceRepository.class);
        liveness = mock(RunnerLiveness.class);
        passage = mock(JarvisWatchPassage.class);
        runs = mock(JarvisWatchRunRepository.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        planner = new JarvisWatchPlanner(watches, workspaces, mock(WorkspaceService.class), liveness, passage, runs, tx,
                Clock.fixed(OffsetDateTime.parse("2026-11-02T09:00:30+01:00").toInstant(), ZoneOffset.UTC),
                Duration.ofMinutes(10));
        planner.setExecutor(Runnable::run);
        subject = Workspace.builder().id(UUID.randomUUID()).userId(userId).hostId(hostId).projectPath("cagip")
                .executionTarget(WorkspaceExecutionTarget.RUNNER).build();
        watch = JarvisWatch.builder().id(UUID.randomUUID()).userId(userId).workspaceId(subject.getId())
                .runTimes("09:00,14:00").timeZone("Europe/Paris").active(true).proposed(false)
                .lastSlotAt(OffsetDateTime.parse("2026-10-30T14:00:00+01:00")).build();
        when(watches.findByActiveTrueAndProposedFalseOrderByIdAsc(any(Pageable.class))).thenReturn(List.of(watch));
        when(workspaces.findByIdAndUserId(subject.getId(), userId)).thenReturn(Optional.of(subject));
        when(liveness.isAlive(userId, hostId)).thenReturn(true);
        when(watches.claimSlot(eq(watch.getId()), any())).thenReturn(1);
    }

    @Test
    void theMondayNineOClockSlotRunsOnce() {
        OffsetDateTime monday = OffsetDateTime.parse("2026-11-02T09:00:30+01:00");
        assertThat(planner.runOnce(monday)).isEqualTo(1);
        verify(passage).run(watch, subject, OffsetDateTime.parse("2026-11-02T09:00:00+01:00"), JarvisWatchRun.SCHEDULED);
        // Un autre pod a pris le créneau : rien ne part.
        when(watches.claimSlot(eq(watch.getId()), any())).thenReturn(0);
        assertThat(planner.runOnce(monday)).isZero();
    }

    @Test
    void nothingRunsOnTheWeekend() {
        watch.setLastSlotAt(OffsetDateTime.parse("2026-10-30T14:00:00+01:00"));
        assertThat(planner.runOnce(OffsetDateTime.parse("2026-10-31T09:30:00+01:00"))).isZero();
        assertThat(planner.runOnce(OffsetDateTime.parse("2026-11-01T14:30:00+01:00"))).isZero();
        verify(passage, never()).run(any(), any(), any(), any());
    }

    @Test
    void anOfflineHostIsNotedMissedOnceThenCaughtUp() {
        when(liveness.isAlive(userId, hostId)).thenReturn(false);
        when(watches.markMissed(eq(watch.getId()), any())).thenReturn(1).thenReturn(0);
        OffsetDateTime nine = OffsetDateTime.parse("2026-11-02T09:00:30+01:00");
        assertThat(planner.runOnce(nine)).isZero();
        assertThat(planner.runOnce(nine.plusMinutes(1))).isZero();
        verify(runs, org.mockito.Mockito.times(1)).save(any());
        verify(passage, never()).run(any(), any(), any(), any());

        when(liveness.isAlive(userId, hostId)).thenReturn(true);
        watch.setMissedSlotAt(OffsetDateTime.parse("2026-11-02T09:00:00+01:00"));
        assertThat(planner.runOnce(OffsetDateTime.parse("2026-11-02T11:00:00+01:00"))).isEqualTo(1);
        verify(passage).run(watch, subject, OffsetDateTime.parse("2026-11-02T09:00:00+01:00"), JarvisWatchRun.CATCH_UP);
    }

    @Test
    void aSlotAlreadyConsumedOrASubjectNoLongerOnAHostDoesNotRun() {
        watch.setLastSlotAt(OffsetDateTime.parse("2026-11-02T09:00:00+01:00"));
        assertThat(planner.runOnce(OffsetDateTime.parse("2026-11-02T09:30:00+01:00"))).isZero();
        watch.setLastSlotAt(null);
        when(workspaces.findByIdAndUserId(subject.getId(), userId)).thenReturn(Optional.empty());
        assertThat(planner.runOnce(OffsetDateTime.parse("2026-11-02T09:30:00+01:00"))).isZero();
        verify(passage, never()).run(any(), any(), any(), any());
    }
}
