package fr.claudegateway.atelier.routine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.routine.RoutineDtos.RoutineRequest;
import fr.claudegateway.atelier.routine.RoutineDtos.RoutineView;
import fr.claudegateway.atelier.routine.RoutineExceptions.InvalidRoutineException;
import fr.claudegateway.atelier.routine.RoutineExceptions.RoutineConflictException;
import fr.claudegateway.atelier.routine.RoutineExceptions.RoutineUnsupportedException;

/** F-183 / SF-183-01 — déclarer une routine : validations, défauts, créneau posé, proposition. */
class RoutineServiceTest {

    /** Vendredi 16 octobre 2026, 9 h à Paris. */
    private static final Instant NOW = OffsetDateTime.parse("2026-10-16T09:00:00+02:00").toInstant();

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private WorkspaceService workspaces;
    private SubjectRoutineRepository repository;
    private RoutineService service;
    private Workspace subject;

    @BeforeEach
    void setUp() {
        workspaces = mock(WorkspaceService.class);
        repository = mock(SubjectRoutineRepository.class);
        service = new RoutineService(workspaces, repository, Clock.fixed(NOW, ZoneOffset.UTC));
        subject = Workspace.builder().id(workspaceId).userId(userId).name("cagip")
                .executionTarget(WorkspaceExecutionTarget.RUNNER).hostId(UUID.randomUUID()).build();
        when(workspaces.requireOwned(userId, workspaceId)).thenReturn(subject);
        when(repository.save(any(SubjectRoutine.class))).thenAnswer(inv -> {
            SubjectRoutine r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            return r;
        });
    }

    private static RoutineRequest weekdays() {
        return new RoutineRequest("Point du matin", "le CR du daily d'aujourd'hui, version scriptée", "WEEKDAYS",
                null, "08:00", "Europe/Paris", null, null);
    }

    @Test
    void createAppliesDefaultsAndPosesTheDueSlot() {
        RoutineView view = service.create(userId, workspaceId, weekdays());
        assertThat(view.maxRunUsd()).isEqualByComparingTo("5.00");
        assertThat(view.active()).isTrue();
        assertThat(view.proposed()).isFalse();
        assertThat(view.origin()).isEqualTo("USER");
        assertThat(view.nextRunAt()).isEqualTo(OffsetDateTime.parse("2026-10-19T08:00:00+02:00"));
        // Le créneau de ce matin est déjà « consommé » : la routine ne part pas tout de suite.
        verify(repository).save(org.mockito.ArgumentMatchers.argThat(r ->
                OffsetDateTime.parse("2026-10-16T08:00:00+02:00").isEqual(r.getLastSlotAt())));
    }

    @Test
    void validationsAreStable() {
        assertThatThrownBy(() -> service.create(userId, workspaceId, new RoutineRequest(" ", "x", "DAILY", null,
                "08:00", null, null, null))).isInstanceOf(InvalidRoutineException.class);
        assertThatThrownBy(() -> service.create(userId, workspaceId, new RoutineRequest("n", "x".repeat(4001),
                "DAILY", null, "08:00", null, null, null))).isInstanceOf(InvalidRoutineException.class);
        assertThatThrownBy(() -> service.create(userId, workspaceId, new RoutineRequest("n", "x", "HOURLY", null,
                "08:00", null, null, null))).isInstanceOf(InvalidRoutineException.class);
        assertThatThrownBy(() -> service.create(userId, workspaceId, new RoutineRequest("n", "x", "WEEKLY", null,
                "08:00", null, null, null))).isInstanceOf(InvalidRoutineException.class);
        assertThatThrownBy(() -> service.create(userId, workspaceId, new RoutineRequest("n", "x", "WEEKLY", 8,
                "08:00", null, null, null))).isInstanceOf(InvalidRoutineException.class);
        assertThatThrownBy(() -> service.create(userId, workspaceId, new RoutineRequest("n", "x", "DAILY", null,
                "8h", null, null, null))).isInstanceOf(InvalidRoutineException.class);
        assertThatThrownBy(() -> service.create(userId, workspaceId, new RoutineRequest("n", "x", "DAILY", null,
                "08:00", "Mars/Olympus", null, null))).isInstanceOf(InvalidRoutineException.class);
        assertThatThrownBy(() -> service.create(userId, workspaceId, new RoutineRequest("n", "x", "DAILY", null,
                "08:00", null, new BigDecimal("0.05"), null))).isInstanceOf(InvalidRoutineException.class);
        assertThatThrownBy(() -> service.create(userId, workspaceId, new RoutineRequest("n", "x", "DAILY", null,
                "08:00", null, new BigDecimal("50.01"), null))).isInstanceOf(InvalidRoutineException.class);
    }

    @Test
    void monthlyBudgetDefaultsAndBounds() {
        RoutineView view = service.create(userId, workspaceId, weekdays());
        assertThat(view.monthlyBudgetUsd()).isEqualByComparingTo("100.00");
        assertThatThrownBy(() -> service.create(userId, workspaceId, new RoutineRequest("n", "x", "DAILY", null,
                "08:00", null, null, null, new BigDecimal("0.50")))).isInstanceOf(InvalidRoutineException.class);
        assertThatThrownBy(() -> service.create(userId, workspaceId, new RoutineRequest("n", "x", "DAILY", null,
                "08:00", null, null, null, new BigDecimal("2000.01")))).isInstanceOf(InvalidRoutineException.class);
        assertThatThrownBy(() -> service.create(userId, workspaceId, new RoutineRequest("n", "x", "DAILY", null,
                "08:00", null, new BigDecimal("10"), null, new BigDecimal("5")))).isInstanceOf(InvalidRoutineException.class);
    }

    @Test
    void budgetsAreTypedInEurosAndKeptInDollars() {
        // F-224 / SF-224-06 : 4,60 € saisis = 5,00 $ rangés (taux 0,92) ; 92 € = 100 $. La vue rend les deux.
        RoutineView view = service.create(userId, workspaceId, new RoutineRequest("n", "x", "DAILY", null,
                "08:00", null, null, null, null, new BigDecimal("4.60"), new BigDecimal("92")));
        assertThat(view.maxRunUsd()).isEqualByComparingTo("5.00");
        assertThat(view.monthlyBudgetUsd()).isEqualByComparingTo("100.00");
        assertThat(view.maxRunEur()).isEqualByComparingTo("4.60");
        assertThat(view.monthlyBudgetEur()).isEqualByComparingTo("92.00");
        assertThat(view.monthSpentEur()).isEqualByComparingTo("0.00");
        // L'euro prime sur le dollar ; ses bornes sont en euros, et le message aussi.
        RoutineView both = service.create(userId, workspaceId, new RoutineRequest("n", "x", "DAILY", null,
                "08:00", null, new BigDecimal("40"), null, null, new BigDecimal("1.84"), null));
        assertThat(both.maxRunUsd()).isEqualByComparingTo("2.00");
        assertThatThrownBy(() -> service.create(userId, workspaceId, new RoutineRequest("n", "x", "DAILY", null,
                "08:00", null, null, null, null, new BigDecimal("50.01"), null)))
                .isInstanceOf(InvalidRoutineException.class).hasMessageContaining("50,00 €");
        assertThatThrownBy(() -> service.create(userId, workspaceId, new RoutineRequest("n", "x", "DAILY", null,
                "08:00", null, null, null, null, null, new BigDecimal("0.99"))))
                .isInstanceOf(InvalidRoutineException.class).hasMessageContaining("1,00 €");
    }

    @Test
    void weeklyKeepsTheDayOthersDropIt() {
        RoutineView weekly = service.create(userId, workspaceId, new RoutineRequest("Semaine", "CR", "weekly", 5,
                "17:00", null, null, null));
        assertThat(weekly.dayOfWeek()).isEqualTo(5);
        assertThat(weekly.cadence()).isEqualTo("WEEKLY");
        RoutineView daily = service.create(userId, workspaceId, new RoutineRequest("Jour", "CR", "DAILY", 5,
                "17:00", null, null, null));
        assertThat(daily.dayOfWeek()).isNull();
    }

    @Test
    void sandboxAndHostTerminalCannotCarryRoutines() {
        subject.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        assertThatThrownBy(() -> service.create(userId, workspaceId, weekdays()))
                .isInstanceOf(RoutineUnsupportedException.class);
        subject.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        subject.setHostTerminal(true);
        assertThatThrownBy(() -> service.create(userId, workspaceId, weekdays()))
                .isInstanceOf(RoutineUnsupportedException.class);
    }

    @Test
    void tenRoutinesAtMost() {
        when(repository.countByUserIdAndWorkspaceId(userId, workspaceId)).thenReturn(10L);
        assertThatThrownBy(() -> service.create(userId, workspaceId, weekdays()))
                .isInstanceOf(RoutineConflictException.class)
                .extracting(e -> ((RoutineConflictException) e).code()).isEqualTo("routine_limit");
        verify(repository, never()).save(any());
    }

    @Test
    void updateKeepsAbsentFieldsAndPausing() {
        SubjectRoutine existing = SubjectRoutine.builder().id(UUID.randomUUID()).userId(userId)
                .workspaceId(workspaceId).name("Point").instruction("CR").cadence(RoutineCadence.DAILY)
                .runTime("08:00").timeZone("Europe/Paris").maxRunUsd(new BigDecimal("5.00"))
                .origin(RoutineOrigin.USER).active(true).createdAt(OffsetDateTime.now()).build();
        when(repository.findByIdAndUserIdAndWorkspaceId(existing.getId(), userId, workspaceId))
                .thenReturn(Optional.of(existing));
        RoutineView paused = service.update(userId, workspaceId, existing.getId(),
                new RoutineRequest(null, null, null, null, null, null, null, false));
        assertThat(paused.active()).isFalse();
        assertThat(paused.name()).isEqualTo("Point");
        assertThat(paused.nextRunAt()).isNull();
    }

    @Test
    void reactivationReslotsSoAPastSlotNeverFires() {
        SubjectRoutine existing = SubjectRoutine.builder().id(UUID.randomUUID()).userId(userId)
                .workspaceId(workspaceId).name("Point").instruction("CR").cadence(RoutineCadence.DAILY)
                .runTime("08:00").timeZone("Europe/Paris").maxRunUsd(new BigDecimal("5.00"))
                .origin(RoutineOrigin.USER).active(false)
                .lastSlotAt(OffsetDateTime.parse("2026-10-01T08:00:00+02:00")).build();
        when(repository.findByIdAndUserIdAndWorkspaceId(existing.getId(), userId, workspaceId))
                .thenReturn(Optional.of(existing));
        service.update(userId, workspaceId, existing.getId(),
                new RoutineRequest(null, null, null, null, null, null, null, true));
        assertThat(existing.getLastSlotAt()).isEqualTo(OffsetDateTime.parse("2026-10-16T08:00:00+02:00"));
    }

    @Test
    void agentProposalIsInactiveUntilAccepted() {
        RoutineView proposed = service.propose(userId, subject, weekdays());
        assertThat(proposed.proposed()).isTrue();
        assertThat(proposed.active()).isFalse();
        assertThat(proposed.origin()).isEqualTo("AGENT");
        assertThat(proposed.nextRunAt()).isNull();

        SubjectRoutine stored = SubjectRoutine.builder().id(proposed.id()).userId(userId).workspaceId(workspaceId)
                .name("Point").instruction("CR").cadence(RoutineCadence.WEEKDAYS).runTime("08:00")
                .timeZone("Europe/Paris").maxRunUsd(new BigDecimal("5.00")).origin(RoutineOrigin.AGENT)
                .proposed(true).active(false).build();
        when(repository.findByIdAndUserIdAndWorkspaceId(proposed.id(), userId, workspaceId))
                .thenReturn(Optional.of(stored));
        RoutineView accepted = service.accept(userId, workspaceId, proposed.id());
        assertThat(accepted.active()).isTrue();
        assertThat(accepted.proposed()).isFalse();
        assertThatThrownBy(() -> service.accept(userId, workspaceId, proposed.id()))
                .isInstanceOf(RoutineConflictException.class);
    }

    @Test
    void proposalForAnotherUsersSubjectIsRefused() {
        assertThatThrownBy(() -> service.propose(UUID.randomUUID(), subject, weekdays()))
                .isInstanceOf(RoutineExceptions.RoutineNotFoundException.class);
    }
}
