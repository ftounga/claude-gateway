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

    /** Lignes au plus dans la bande en tête du fil (SF-183-04). */
    public static final int UNSEEN = 5;

    private final RoutineRunRepository runs;
    private final Clock clock;
    /** Les attentes du sujet (F-154), pour compter ce qu'une exécution a mis à jour (SF-183-04). */
    private fr.claudegateway.atelier.actions.TerminalActionRepository actions;
    /** Les routines, pour nommer une exécution dans la bande (SF-183-04). */
    private SubjectRoutineRepository routines;

    public RoutineRunService(RoutineRunRepository runs, Clock clock) {
        this.runs = runs;
        this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReportSources(fr.claudegateway.atelier.actions.TerminalActionRepository actions,
            SubjectRoutineRepository routines) {
        this.actions = actions;
        this.routines = routines;
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
                .startedAt(now).finishedAt(now).costUsd(BigDecimal.ZERO).errorCode(errorCode)
                .summary(RoutineReport.summary(routine.getName(), status, null)).build());
    }

    /** Clôt une exécution. Une exécution déjà close n'est pas réécrite. */
    @Transactional
    public RoutineRun finish(UUID runId, RoutineRunStatus status, BigDecimal costUsd, String errorCode) {
        return finish(runId, status, costUsd, errorCode, null);
    }

    /**
     * Clôt une exécution et écrit son compte rendu (SF-183-04) : les attentes du sujet créées ou modifiées
     * pendant l'exécution, et la ligne « Nom — 3 attentes mises à jour ». Un comptage en échec ne fait pas
     * échouer la clôture : la ligne dit alors « terminée ».
     */
    @Transactional
    public RoutineRun finish(UUID runId, RoutineRunStatus status, BigDecimal costUsd, String errorCode,
            String routineName) {
        RoutineRun run = runs.findById(runId).orElse(null);
        if (run == null || run.getStatus().finished()) {
            return run;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        run.setStatus(status);
        run.setFinishedAt(now);
        run.setCostUsd(costUsd == null ? BigDecimal.ZERO : costUsd);
        run.setErrorCode(errorCode);
        Integer attentes = countAttentes(run, now);
        run.setAttentesUpdated(attentes);
        run.setSummary(RoutineReport.summary(routineName, status, attentes));
        return runs.save(run);
    }

    private Integer countAttentes(RoutineRun run, OffsetDateTime now) {
        if (actions == null) {
            return null;
        }
        try {
            return (int) actions.countByUserIdAndWorkspaceIdAndUpdatedAtBetween(run.getUserId(),
                    run.getWorkspaceId(), run.getStartedAt(), now);
        } catch (RuntimeException e) {
            log.warn("Compte des attentes d'une routine impossible : {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** Les exécutions closes non acquittées du sujet (SF-183-04), nommées. Le sujet a été relu possédé. */
    @Transactional(readOnly = true)
    public List<RoutineRunView> unseen(UUID userId, UUID workspaceId) {
        List<RoutineRun> rows = runs.findUnseen(userId, workspaceId, PageRequest.of(0, UNSEEN));
        java.util.Map<UUID, String> names = new java.util.HashMap<>();
        if (routines != null) {
            routines.findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(userId, workspaceId)
                    .forEach(r -> names.put(r.getId(), r.getName()));
        }
        return rows.stream().map(run -> RoutineRunView.of(run, names.get(run.getRoutineId()))).toList();
    }

    /** Acquitte une ligne de la bande (SF-183-04) ; idempotent. 404 si l'exécution n'est pas à ce sujet. */
    @Transactional
    public void acknowledge(UUID userId, UUID workspaceId, UUID runId) {
        RoutineRun run = runs.findByIdAndUserIdAndWorkspaceId(runId, userId, workspaceId)
                .orElseThrow(RoutineExceptions.RoutineNotFoundException::new);
        if (run.getAcknowledgedAt() == null) {
            run.setAcknowledgedAt(OffsetDateTime.now(clock));
            runs.save(run);
        }
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

    /**
     * Dépense de la routine depuis le 1er du mois, <b>dans son fuseau</b> (SF-183-03) : la somme des
     * coûts de ses exécutions — y compris celle qui tourne (son coût est écrit à la fin).
     */
    @Transactional(readOnly = true)
    public BigDecimal monthSpent(SubjectRoutine routine, OffsetDateTime now) {
        java.time.ZoneId zone = RoutineSlots.parseZone(routine.getTimeZone()).orElse(java.time.ZoneId.of("Europe/Paris"));
        OffsetDateTime from = now.atZoneSameInstant(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
                .toOffsetDateTime();
        BigDecimal spent = runs.spentSince(routine.getId(), routine.getUserId(), from);
        return spent == null ? BigDecimal.ZERO : spent;
    }

    /** La dernière exécution d'une routine, ou {@code null}. */
    @Transactional(readOnly = true)
    public RoutineRunView last(UUID userId, UUID routineId) {
        return runs.findFirstByRoutineIdAndUserIdOrderByStartedAtDesc(routineId, userId)
                .map(RoutineRunView::of).orElse(null);
    }
}
