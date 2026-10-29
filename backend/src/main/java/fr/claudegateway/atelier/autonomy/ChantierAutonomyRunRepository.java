package fr.claudegateway.atelier.autonomy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Le journal des courses autonomes — toute lecture filtre par {@code user_id} (F-219 / SF-219-02). */
public interface ChantierAutonomyRunRepository extends JpaRepository<ChantierAutonomyRun, UUID> {

    /** Les courses en cours d'un compte. */
    long countByUserIdAndStatus(UUID userId, AutonomyRunStatus status);

    /** La course en cours d'un sujet, s'il y en a une. */
    Optional<ChantierAutonomyRun> findFirstByUserIdAndWorkspaceIdAndStatusOrderByStartedAtDesc(UUID userId,
            UUID workspaceId, AutonomyRunStatus status);

    /** La course en cours d'un chantier du sujet, s'il y en a une (F-221 / SF-221-02). */
    Optional<ChantierAutonomyRun> findFirstByUserIdAndWorkspaceIdAndChantierNumberAndStatusOrderByStartedAtDesc(
            UUID userId, UUID workspaceId, int chantierNumber, AutonomyRunStatus status);

    /** Les courses en cours d'un sujet, la plus récente d'abord (F-221 / SF-221-02). */
    List<ChantierAutonomyRun> findByUserIdAndWorkspaceIdAndStatusOrderByStartedAtDesc(UUID userId, UUID workspaceId,
            AutonomyRunStatus status);

    /** Les courses d'un sujet, la plus récente d'abord. */
    List<ChantierAutonomyRun> findByUserIdAndWorkspaceIdOrderByStartedAtDesc(UUID userId, UUID workspaceId,
            Pageable page);

    /** Les courses d'un compte, la plus récente d'abord. */
    List<ChantierAutonomyRun> findByUserIdOrderByStartedAtDesc(UUID userId, Pageable page);

    /** Les courses en cours d'un compte. */
    List<ChantierAutonomyRun> findByUserIdAndStatusOrderByStartedAtDesc(UUID userId, AutonomyRunStatus status);

    /** Les courses en cours sans signe de vie depuis avant cette limite : un pod mort ne les finira jamais. */
    @Query("select r from ChantierAutonomyRun r where r.status = "
            + "fr.claudegateway.atelier.autonomy.AutonomyRunStatus.RUNNING and r.heartbeatAt < :before")
    List<ChantierAutonomyRun> findStaleRunning(@Param("before") OffsetDateTime before);

    /** Signe de vie après un tour — seulement si la course tourne encore. */
    @Modifying
    @Query("update ChantierAutonomyRun r set r.heartbeatAt = :now, r.turns = :turns, r.costUsd = :cost, "
            + "r.stepsDone = :done, r.stepsTotal = :total where r.id = :id and r.status = "
            + "fr.claudegateway.atelier.autonomy.AutonomyRunStatus.RUNNING")
    int heartbeat(@Param("id") UUID id, @Param("now") OffsetDateTime now, @Param("turns") int turns,
            @Param("cost") BigDecimal cost, @Param("done") int done, @Param("total") int total);

    /** Clôt la course — seulement si elle tourne encore : une reprise en main n'est jamais réécrite. */
    @Modifying
    @Query("update ChantierAutonomyRun r set r.status = :status, r.finishedAt = :now, r.heartbeatAt = :now, "
            + "r.stopReason = :reason, r.errorCode = :code where r.id = :id and r.status = "
            + "fr.claudegateway.atelier.autonomy.AutonomyRunStatus.RUNNING")
    int finish(@Param("id") UUID id, @Param("status") AutonomyRunStatus status, @Param("now") OffsetDateTime now,
            @Param("reason") String reason, @Param("code") String code);
}
