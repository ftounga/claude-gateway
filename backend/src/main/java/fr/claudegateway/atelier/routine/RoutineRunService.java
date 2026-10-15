package fr.claudegateway.atelier.routine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.routine.RoutineDtos.RoutineRunView;

/**
 * <b>Le journal des exécutions</b> (F-183 / SF-183-02) : ouvrir une exécution, la clore, noter un
 * créneau manqué, relire l'historique, et clore les exécutions qu'un pod mort ne finira jamais.
 *
 * <p>Aucun contenu de tour n'est écrit : des instants, un statut, un coût, un code. Les lectures
 * passent par l'identité du propriétaire ({@code user_id} + {@code workspace_id}).</p>
 */
@Service
public class RoutineRunService {

    /** Lignes d'historique rendues par routine. */
    public static final int HISTORY = 20;

    private static final Logger log = LoggerFactory.getLogger(RoutineRunService.class);

    private final RoutineRunRepository runs;
    private final Clock clock;

    public RoutineRunService(RoutineRunRepository runs, Clock clock) {
        this.runs = runs;
        this.clock = clock;
    }

    /** Ouvre une exécution : {@code RUNNING}, {@code started_at} maintenant. */
    @Transactional
    public RoutineRun open(SubjectRoutine routine, RoutineTrigger trigger, OffsetDateTime slot, UUID turnId,
            BigDecimal capUsd) {
        return runs.save(RoutineRun.builder().routineId(routine.getId()).userId(routine.getUserId())
                .workspaceId(routine.getWorkspaceId()).trigger(trigger).slotAt(slot)
                .status(RoutineRunStatus.RUNNING).startedAt(OffsetDateTime.now(clock)).turnId(turnId)
                .capUsd(capUsd).build());
    }

    /** Une exécution qui n'a pas tourné (créneau manqué, budget épuisé) : ouverte et close d'un coup. */
    @Transactional
    public RoutineRun recordClosed(SubjectRoutine routine, RoutineTrigger trigger, OffsetDateTime slot,
            RoutineRunStatus status, String errorCode) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return runs.save(RoutineRun.builder().routineId(routine.getId()).userId(routine.getUserId())
                .workspaceId(routine.getWorkspaceId()).trigger(trigger).slotAt(slot).status(status)
                .startedAt(now).finishedAt(now).costUsd(BigDecimal.ZERO).errorCode(errorCode).build());
    }

    /** Clôt une exécution. Une exécution déjà close n'est pas réécrite. */
    @Transactional
    public RoutineRun finish(UUID runId, RoutineRunStatus status, BigDecimal costUsd, String errorCode) {
        RoutineRun run = runs.findById(runId).orElse(null);
        if (run == null || run.getStatus().finished()) {
            return run;
        }
        run.setStatus(status);
        run.setFinishedAt(OffsetDateTime.now(clock));
        run.setCostUsd(costUsd == null ? BigDecimal.ZERO : costUsd);
        run.setErrorCode(errorCode);
        return runs.save(run);
    }

    /**
     * Clôt {@code FAILED / interrupted} les exécutions {@code RUNNING} plus vieilles que {@code staleAfter} :
     * le pod qui les portait est mort (un tour dure au plus deux budgets de tour). Sans cela, la
     * vérification avant déploiement (D5) croirait un travail en cours pour toujours.
     *
     * @return nombre d'exécutions closes
     */
    @Transactional
    public int closeStale(Duration staleAfter) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<RoutineRun> stale = runs.findStaleRunning(now.minus(staleAfter));
        for (RoutineRun run : stale) {
            run.setStatus(RoutineRunStatus.FAILED);
            run.setFinishedAt(now);
            run.setErrorCode("interrupted");
            runs.save(run);
        }
        if (!stale.isEmpty()) {
            log.warn("Exécutions de routine orphelines closes : {}", stale.size());
        }
        return stale.size();
    }

    /** L'historique d'une routine (la routine a déjà été relue sous son propriétaire). */
    @Transactional(readOnly = true)
    public List<RoutineRunView> history(UUID userId, UUID workspaceId, UUID routineId) {
        return runs.findByRoutineIdAndUserIdAndWorkspaceIdOrderByStartedAtDesc(routineId, userId, workspaceId,
                PageRequest.of(0, HISTORY)).stream().map(RoutineRunView::of).toList();
    }

    /** La dernière exécution d'une routine, ou {@code null}. */
    @Transactional(readOnly = true)
    public RoutineRunView last(UUID userId, UUID routineId) {
        return runs.findFirstByRoutineIdAndUserIdOrderByStartedAtDesc(routineId, userId)
                .map(RoutineRunView::of).orElse(null);
    }
}
