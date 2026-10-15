package fr.claudegateway.atelier.routine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.atelier.routine.RoutineExceptions.RoutineConflictException;
import fr.claudegateway.runner.RunnerLiveness;

/**
 * F-183 / SF-183-02 — le planificateur : à l'heure, poste en ligne, sujet libre ⇒ un tour ; week-end,
 * créneau traité, sujet occupé ⇒ rien ; poste hors ligne ⇒ une seule ligne manquée, puis un seul
 * rattrapage ; verrou perdu ⇒ rien.
 */
class RoutinePlannerTest {

    /** Lundi 19 octobre 2026, 08:00:30 à Paris. */
    private static final OffsetDateTime MONDAY = OffsetDateTime.parse("2026-10-19T08:00:30+02:00");
    private static final OffsetDateTime MONDAY_SLOT = OffsetDateTime.parse("2026-10-19T08:00:00+02:00");

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private SubjectRoutineRepository routines;
    private WorkspaceRepository workspaces;
    private WorkspaceService workspaceService;
    private RunnerLiveness liveness;
    private LiveTurnRegistry liveTurns;
    private RoutineTurnRunner runner;
    private RoutineRunService runs;
    private RoutinePlanner planner;
    private SubjectRoutine routine;
    private Workspace subject;
    private LiveTurn preparedTurn;

    @BeforeEach
    void setUp() {
        routines = mock(SubjectRoutineRepository.class);
        workspaces = mock(WorkspaceRepository.class);
        workspaceService = mock(WorkspaceService.class);
        liveness = mock(RunnerLiveness.class);
        liveTurns = mock(LiveTurnRegistry.class);
        runner = mock(RoutineTurnRunner.class);
        runs = mock(RoutineRunService.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        planner = new RoutinePlanner(routines, workspaces, workspaceService, liveness, liveTurns, runner, runs, tx,
                Clock.fixed(MONDAY.toInstant(), ZoneOffset.UTC), Duration.ofMinutes(10), Duration.ofHours(3));
        routine = SubjectRoutine.builder().id(UUID.randomUUID()).userId(userId).workspaceId(workspaceId)
                .name("Point du matin").instruction("le CR du daily d'aujourd'hui, version scriptée")
                .cadence(RoutineCadence.WEEKDAYS).runTime("08:00").timeZone("Europe/Paris")
                .maxRunUsd(new BigDecimal("5.00")).origin(RoutineOrigin.USER).active(true)
                .lastSlotAt(OffsetDateTime.parse("2026-10-16T08:00:00+02:00")).build();
        subject = Workspace.builder().id(workspaceId).userId(userId).name("cagip").hostId(hostId)
                .executionTarget(WorkspaceExecutionTarget.RUNNER).build();
        when(workspaces.findByIdAndUserId(workspaceId, userId)).thenReturn(Optional.of(subject));
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(subject);
        when(liveness.isAlive(userId, hostId)).thenReturn(true);
        when(liveTurns.find(userId, workspaceId)).thenReturn(Optional.empty());
        preparedTurn = new LiveTurnRegistry(new ObjectMapper()).open(userId, workspaceId);
        when(runner.prepare(routine)).thenReturn(Optional.of(preparedTurn));
        when(routines.claimSlot(routine.getId(), MONDAY_SLOT)).thenReturn(1);
        when(routines.findByActiveTrueAndProposedFalseOrderByIdAsc(any())).thenReturn(List.of(routine));
        when(runs.monthSpent(any(), any())).thenReturn(BigDecimal.ZERO);
        when(runner.launch(any(), any(), any(), any(), any())).thenReturn(RoutineRun.builder()
                .trigger(RoutineTrigger.MANUAL).status(RoutineRunStatus.RUNNING).build());
    }

    @Test
    void onTheHourWithTheHostOnlineATurnStarts() {
        assertThat(planner.runOnce(MONDAY)).isEqualTo(1);
        verify(runner).launch(preparedTurn, routine, RoutineTrigger.SCHEDULED, MONDAY_SLOT, new BigDecimal("5.00"));
        verify(runs).closeStale(Duration.ofHours(3));
    }

    @Test
    void saturdayNothingDueSinceFridayWasConsumed() {
        routine.setLastSlotAt(OffsetDateTime.parse("2026-10-16T08:00:00+02:00"));
        assertThat(planner.plan(routine, OffsetDateTime.parse("2026-10-17T08:00:30+02:00"))).isFalse();
        verify(runner, never()).prepare(any());
    }

    @Test
    void alreadyConsumedSlotDoesNothing() {
        routine.setLastSlotAt(MONDAY_SLOT);
        assertThat(planner.plan(routine, MONDAY)).isFalse();
        verify(runner, never()).prepare(any());
    }

    @Test
    void hostOfflineRecordsOneMissedRunAndKeepsTheSlot() {
        when(liveness.isAlive(userId, hostId)).thenReturn(false);
        when(routines.markMissed(routine.getId(), MONDAY_SLOT)).thenReturn(1).thenReturn(0);
        assertThat(planner.plan(routine, MONDAY)).isFalse();
        assertThat(planner.plan(routine, MONDAY.plusMinutes(1))).isFalse();
        verify(runs, times(1)).recordClosed(routine, RoutineTrigger.SCHEDULED, MONDAY_SLOT,
                RoutineRunStatus.MISSED_OFFLINE, "host_offline");
        verify(routines, never()).claimSlot(any(), any());
    }

    @Test
    void aMissedSlotIsNotifiedOnce() {
        RoutineNotifier notifier = mock(RoutineNotifier.class);
        planner.setNotifier(notifier);
        when(liveness.isAlive(userId, hostId)).thenReturn(false);
        when(routines.markMissed(routine.getId(), MONDAY_SLOT)).thenReturn(1).thenReturn(0);
        planner.plan(routine, MONDAY);
        planner.plan(routine, MONDAY.plusMinutes(1));
        verify(notifier, times(1)).notify(routine, RoutineRunStatus.MISSED_OFFLINE);
    }

    @Test
    void reconnectionCatchesUpOnceAsCatchUp() {
        routine.setMissedSlotAt(MONDAY_SLOT);
        OffsetDateTime later = OffsetDateTime.parse("2026-10-19T11:00:00+02:00");
        assertThat(planner.plan(routine, later)).isTrue();
        verify(runner).launch(preparedTurn, routine, RoutineTrigger.CATCH_UP, MONDAY_SLOT, new BigDecimal("5.00"));
    }

    @Test
    void afterSeveralMissedDaysOnlyTheLastSlotRuns() {
        routine.setLastSlotAt(OffsetDateTime.parse("2026-10-14T08:00:00+02:00"));
        routine.setMissedSlotAt(OffsetDateTime.parse("2026-10-16T08:00:00+02:00"));
        assertThat(planner.plan(routine, MONDAY)).isTrue();
        verify(runner, times(1)).launch(eq(preparedTurn), eq(routine), eq(RoutineTrigger.SCHEDULED),
                eq(MONDAY_SLOT), any());
    }

    @Test
    void busySubjectIsLeftAloneAndRetried() {
        when(liveTurns.find(userId, workspaceId)).thenReturn(Optional.of(preparedTurn));
        assertThat(planner.plan(routine, MONDAY)).isFalse();
        verify(runner, never()).prepare(any());
        verify(routines, never()).claimSlot(any(), any());
    }

    @Test
    void lostLockAbandonsThePreparedTurn() {
        when(routines.claimSlot(routine.getId(), MONDAY_SLOT)).thenReturn(0);
        assertThat(planner.plan(routine, MONDAY)).isFalse();
        verify(runner).abandon(preparedTurn);
        verify(runner, never()).launch(any(), any(), any(), any(), any());
    }

    @Test
    void subjectNoLongerOnAHostDoesNothing() {
        subject.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        assertThat(planner.plan(routine, MONDAY)).isFalse();
        when(workspaces.findByIdAndUserId(workspaceId, userId)).thenReturn(Optional.empty());
        assertThat(planner.plan(routine, MONDAY)).isFalse();
        verify(liveness, never()).isAlive(any(), any());
    }

    @Test
    void lateStartBeyondGraceIsACatchUp() {
        assertThat(planner.plan(routine, MONDAY.plusMinutes(15))).isTrue();
        verify(runner).launch(any(), any(), eq(RoutineTrigger.CATCH_UP), eq(MONDAY_SLOT), any());
    }

    @Test
    void monthlyBudgetExhaustedSkipsTheSlotOnce() {
        when(runs.monthSpent(any(), any())).thenReturn(new BigDecimal("100.00"));
        assertThat(planner.plan(routine, MONDAY)).isFalse();
        verify(runs).recordClosed(routine, RoutineTrigger.SCHEDULED, MONDAY_SLOT, RoutineRunStatus.SKIPPED_BUDGET,
                "monthly_budget");
        verify(runner, never()).prepare(any());
    }

    @Test
    void theCapIsWhatIsLeftOfTheMonthWhenLessThanThePerRunBudget() {
        when(runs.monthSpent(any(), any())).thenReturn(new BigDecimal("98.80"));
        assertThat(planner.plan(routine, MONDAY)).isTrue();
        verify(runner).launch(preparedTurn, routine, RoutineTrigger.SCHEDULED, MONDAY_SLOT, new BigDecimal("1.20"));
    }

    @Test
    void runNowRefusesWhenTheMonthIsExhausted() {
        when(routines.findByIdAndUserIdAndWorkspaceId(routine.getId(), userId, workspaceId))
                .thenReturn(Optional.of(routine));
        when(runs.monthSpent(any(), any())).thenReturn(new BigDecimal("100.00"));
        assertThatThrownBy(() -> planner.runNow(userId, workspaceId, routine.getId()))
                .extracting(e -> ((RoutineConflictException) e).code()).isEqualTo("routine_budget_exhausted");
    }

    @Test
    void runNowGuards() {
        when(routines.findByIdAndUserIdAndWorkspaceId(routine.getId(), userId, workspaceId))
                .thenReturn(Optional.of(routine));
        planner.runNow(userId, workspaceId, routine.getId());
        verify(runner).launch(eq(preparedTurn), eq(routine), eq(RoutineTrigger.MANUAL), isNull(), any());

        when(liveness.isAlive(userId, hostId)).thenReturn(false);
        assertThatThrownBy(() -> planner.runNow(userId, workspaceId, routine.getId()))
                .isInstanceOf(RoutineConflictException.class)
                .extracting(e -> ((RoutineConflictException) e).code()).isEqualTo("routine_host_offline");

        when(liveness.isAlive(userId, hostId)).thenReturn(true);
        when(liveTurns.find(userId, workspaceId)).thenReturn(Optional.of(preparedTurn));
        assertThatThrownBy(() -> planner.runNow(userId, workspaceId, routine.getId()))
                .extracting(e -> ((RoutineConflictException) e).code()).isEqualTo("routine_busy");

        routine.setProposed(true);
        assertThatThrownBy(() -> planner.runNow(userId, workspaceId, routine.getId()))
                .extracting(e -> ((RoutineConflictException) e).code()).isEqualTo("routine_conflict");
    }
}
