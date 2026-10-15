package fr.claudegateway.atelier.routine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.atelier.routine.RoutineDtos.RoutineRunView;
import fr.claudegateway.atelier.routine.RoutineExceptions.RoutineConflictException;
import fr.claudegateway.atelier.routine.RoutineExceptions.RoutineNotFoundException;
import fr.claudegateway.atelier.routine.RoutineExceptions.RoutineUnsupportedException;
import fr.claudegateway.runner.RunnerLiveness;

/**
 * <b>Le planificateur des routines</b> (F-183 / SF-183-02, D2) : à chaque passage, chaque routine
 * programmée dont le créneau est dû part — poste en ligne et sujet libre —, ou est notée manquée.
 *
 * <p><b>Rien ne dépend d'un pod</b> : le créneau consommé est en base et l'avance est un {@code UPDATE}
 * conditionnel ({@link SubjectRoutineRepository#claimSlot}) — deux pods au même créneau n'en lancent
 * qu'un. <b>Rattrapage une seule fois</b> : tant que le poste est hors ligne, le créneau n'est pas
 * consommé ; à la reconnexion, seul le <b>dernier</b> créneau dû part, une fois.</p>
 *
 * <p><b>Isolation à chaque exécution</b> : le sujet est relu par {@code findByIdAndUserId}, le poste
 * interrogé sous {@code (userId, hostId)}.</p>
 */
@Service
public class RoutinePlanner {

    /** Routines examinées par page. */
    static final int PAGE = 200;

    private static final Logger log = LoggerFactory.getLogger(RoutinePlanner.class);

    private final SubjectRoutineRepository routines;
    private final WorkspaceRepository workspaces;
    private final WorkspaceService workspaceService;
    private final RunnerLiveness liveness;
    private final LiveTurnRegistry liveTurns;
    private final RoutineTurnRunner runner;
    private final RoutineRunService runs;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration catchUpGrace;
    private final Duration staleAfter;

    public RoutinePlanner(SubjectRoutineRepository routines, WorkspaceRepository workspaces,
            WorkspaceService workspaceService, RunnerLiveness liveness, LiveTurnRegistry liveTurns,
            RoutineTurnRunner runner, RoutineRunService runs, PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${app.routines.catch-up-grace:PT10M}") Duration catchUpGrace,
            @Value("${app.routines.stale-after:PT3H}") Duration staleAfter) {
        this.routines = routines;
        this.workspaces = workspaces;
        this.workspaceService = workspaceService;
        this.liveness = liveness;
        this.liveTurns = liveTurns;
        this.runner = runner;
        this.runs = runs;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.catchUpGrace = catchUpGrace;
        this.staleAfter = staleAfter;
    }

    /** Un passage, maintenant. */
    public int runOnce() {
        return runOnce(OffsetDateTime.now(clock));
    }

    /**
     * Un passage : exécutions orphelines closes, créneaux dus lancés ou notés manqués.
     *
     * @return nombre d'exécutions lancées
     */
    public int runOnce(OffsetDateTime now) {
        try {
            runs.closeStale(staleAfter);
        } catch (RuntimeException e) {
            log.warn("Clôture des exécutions orphelines en échec : {}", e.getClass().getSimpleName());
        }
        int started = 0;
        for (int page = 0;; page++) {
            List<SubjectRoutine> rows = routines.findByActiveTrueAndProposedFalseOrderByIdAsc(PageRequest.of(page, PAGE));
            for (SubjectRoutine routine : rows) {
                try {
                    if (plan(routine, now)) {
                        started++;
                    }
                } catch (RuntimeException e) {
                    log.warn("Planification de routine en échec (routine={}) : {}", routine.getId(),
                            e.getClass().getSimpleName());
                }
            }
            if (rows.size() < PAGE) {
                return started;
            }
        }
    }

    /** Examine une routine ; vrai si une exécution est partie. */
    boolean plan(SubjectRoutine routine, OffsetDateTime now) {
        Optional<OffsetDateTime> dueSlot = RoutineSlots.dueSlot(routine, now);
        if (dueSlot.isEmpty()) {
            return false;
        }
        OffsetDateTime due = dueSlot.get();
        if (routine.getLastSlotAt() != null && !routine.getLastSlotAt().isBefore(due)) {
            return false; // créneau déjà traité
        }
        Optional<Workspace> workspace = workspaces.findByIdAndUserId(routine.getWorkspaceId(), routine.getUserId());
        if (workspace.isEmpty() || !RoutineService.isRoutineCapable(workspace.get())) {
            return false; // le sujet n'est plus sur un poste : rien ne part
        }
        UUID hostId = workspace.get().getHostId();
        if (!liveness.isAlive(routine.getUserId(), hostId)) {
            recordMissedOnce(routine, due);
            return false; // poste hors ligne : rattrapage à la reconnexion
        }
        if (liveTurns.find(routine.getUserId(), routine.getWorkspaceId()).isPresent()) {
            return false; // l'utilisateur travaille : on réessaie à la minute suivante
        }
        Optional<LiveTurn> turn = runner.prepare(routine);
        if (turn.isEmpty()) {
            return false;
        }
        Integer claimed = transactions.execute(status -> routines.claimSlot(routine.getId(), due));
        if (claimed == null || claimed == 0) {
            runner.abandon(turn.get()); // un autre pod a pris ce créneau
            return false;
        }
        boolean missed = routine.getMissedSlotAt() != null && routine.getMissedSlotAt().isEqual(due);
        RoutineTrigger trigger = missed || now.isAfter(due.plus(catchUpGrace))
                ? RoutineTrigger.CATCH_UP : RoutineTrigger.SCHEDULED;
        runner.launch(turn.get(), routine, trigger, due, capFor(routine));
        return true;
    }

    /** Plafond en dollars de l'exécution (SF-183-03 l'affine par le budget du mois). */
    BigDecimal capFor(SubjectRoutine routine) {
        return routine.getMaxRunUsd();
    }

    /** Note le créneau manqué — une seule ligne par créneau, quel que soit le nombre de passages. */
    private void recordMissedOnce(SubjectRoutine routine, OffsetDateTime due) {
        transactions.executeWithoutResult(status -> {
            if (routines.markMissed(routine.getId(), due) == 1) {
                runs.recordClosed(routine, RoutineTrigger.SCHEDULED, due, RoutineRunStatus.MISSED_OFFLINE,
                        "host_offline");
                onMissed(routine);
            }
        });
    }

    /** Point d'extension : un créneau vient d'être noté manqué (SF-183-04 y notifie). */
    void onMissed(SubjectRoutine routine) {
        // SF-183-02 : rien de plus que la ligne MISSED_OFFLINE.
    }

    /**
     * « Lancer maintenant » (SF-183-02) : mêmes gardes qu'à l'heure dite — sujet sur poste, poste en ligne,
     * sujet libre. Le créneau programmé n'est pas consommé.
     */
    public RoutineRunView runNow(UUID userId, UUID workspaceId, UUID routineId) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        SubjectRoutine routine = routines.findByIdAndUserIdAndWorkspaceId(routineId, userId, workspaceId)
                .orElseThrow(RoutineNotFoundException::new);
        if (routine.isProposed()) {
            throw new RoutineConflictException("routine_conflict",
                    "Cette routine est une proposition : activez-la d'abord.");
        }
        if (!RoutineService.isRoutineCapable(workspace)) {
            throw new RoutineUnsupportedException(
                    "Une routine tourne sur le poste d'un sujet : ce terminal ne peut pas en porter.");
        }
        if (!liveness.isAlive(userId, workspace.getHostId())) {
            throw new RoutineConflictException("routine_host_offline",
                    "Le poste est hors ligne : la routine ne peut pas tourner maintenant.");
        }
        Optional<LiveTurn> turn = liveTurns.find(userId, workspaceId).isPresent()
                ? Optional.empty() : runner.prepare(routine);
        if (turn.isEmpty()) {
            throw new RoutineConflictException("routine_busy",
                    "Un tour tourne déjà dans ce sujet : réessayez quand il sera fini.");
        }
        return RoutineRunView.of(runner.launch(turn.get(), routine, RoutineTrigger.MANUAL, null, capFor(routine)));
    }
}
