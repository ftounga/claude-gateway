package fr.claudegateway.atelier.autonomy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Le journal des courses autonomes</b> (F-219 / SF-219-02) : chaque écriture dans sa propre transaction
 * (la boucle tourne hors transaction, en tâche de fond). Toute lecture filtre par {@code user_id}.
 */
@Service
public class AutonomyRunStore {

    private final ChantierAutonomyRunRepository repository;
    private final Clock clock;

    public AutonomyRunStore(ChantierAutonomyRunRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Écrit la course {@code RUNNING}. */
    @Transactional
    public ChantierAutonomyRun open(UUID userId, UUID workspaceId, int number, String title, UUID turnId,
            int stepsDone, int stepsTotal) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return repository.save(ChantierAutonomyRun.builder()
                .userId(userId)
                .workspaceId(workspaceId)
                .chantierNumber(number)
                .chantierTitle(title == null ? null : title.length() > 200 ? title.substring(0, 200) : title)
                .status(AutonomyRunStatus.RUNNING)
                .turnId(turnId)
                .stepsDone(stepsDone)
                .stepsTotal(stepsTotal)
                .costUsd(BigDecimal.ZERO)
                .startedAt(now)
                .heartbeatAt(now)
                .build());
    }

    /** Signe de vie après un tour ; sans effet si la course n'est plus en cours. */
    @Transactional
    public void heartbeat(UUID runId, int turns, BigDecimal cost, int done, int total) {
        repository.heartbeat(runId, OffsetDateTime.now(clock), turns, cost == null ? BigDecimal.ZERO : cost, done,
                total);
    }

    /**
     * Clôt la course si elle tourne encore.
     *
     * @return vrai si c'est cet appel qui l'a close (faux : déjà close, par exemple reprise en main)
     */
    @Transactional
    public boolean finish(UUID runId, AutonomyRunStatus status, String reason, String code) {
        String bounded = reason == null ? null
                : reason.length() > ChantierAutonomyRun.MAX_REASON
                        ? reason.substring(0, ChantierAutonomyRun.MAX_REASON - 1) + "…" : reason;
        return repository.finish(runId, status, OffsetDateTime.now(clock), bounded, code) == 1;
    }

    /** Vrai si la course tourne encore (relu en base : une reprise en main vient peut-être d'un autre pod). */
    @Transactional(readOnly = true)
    public boolean isRunning(UUID runId) {
        return repository.findById(runId).map(r -> r.getStatus() == AutonomyRunStatus.RUNNING).orElse(false);
    }

    @Transactional(readOnly = true)
    public Optional<ChantierAutonomyRun> running(UUID userId, UUID workspaceId) {
        return repository.findFirstByUserIdAndWorkspaceIdAndStatusOrderByStartedAtDesc(userId, workspaceId,
                AutonomyRunStatus.RUNNING);
    }

    @Transactional(readOnly = true)
    public long countRunning(UUID userId) {
        return repository.countByUserIdAndStatus(userId, AutonomyRunStatus.RUNNING);
    }

    @Transactional(readOnly = true)
    public Optional<ChantierAutonomyRun> find(UUID runId) {
        return repository.findById(runId);
    }

    /** Les 20 dernières courses d'un sujet. */
    @Transactional(readOnly = true)
    public List<ChantierAutonomyRun> recentOf(UUID userId, UUID workspaceId) {
        return repository.findByUserIdAndWorkspaceIdOrderByStartedAtDesc(userId, workspaceId, PageRequest.of(0, 20));
    }

    /** Les courses en cours d'un compte, puis les 20 dernières. */
    @Transactional(readOnly = true)
    public List<ChantierAutonomyRun> ofUser(UUID userId) {
        List<ChantierAutonomyRun> result = new java.util.ArrayList<>(
                repository.findByUserIdAndStatusOrderByStartedAtDesc(userId, AutonomyRunStatus.RUNNING));
        for (ChantierAutonomyRun run : repository.findByUserIdOrderByStartedAtDesc(userId, PageRequest.of(0, 20))) {
            if (run.getStatus() != AutonomyRunStatus.RUNNING) {
                result.add(run);
            }
        }
        return List.copyOf(result);
    }

    /** Les courses sans signe de vie depuis avant {@code before}. */
    @Transactional(readOnly = true)
    public List<ChantierAutonomyRun> staleRunning(OffsetDateTime before) {
        return repository.findStaleRunning(before);
    }
}
