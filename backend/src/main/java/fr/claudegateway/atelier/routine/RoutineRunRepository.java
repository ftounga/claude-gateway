package fr.claudegateway.atelier.routine;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Le journal des exécutions — lectures sous {@code user_id} + {@code workspace_id} (F-183 / SF-183-02). */
public interface RoutineRunRepository extends JpaRepository<RoutineRun, UUID> {

    /** L'historique d'une routine, le plus récent d'abord. */
    List<RoutineRun> findByRoutineIdAndUserIdAndWorkspaceIdOrderByStartedAtDesc(UUID routineId, UUID userId,
            UUID workspaceId, Pageable page);

    /** La dernière exécution d'une routine. */
    Optional<RoutineRun> findFirstByRoutineIdAndUserIdOrderByStartedAtDesc(UUID routineId, UUID userId);

    Optional<RoutineRun> findByIdAndUserIdAndWorkspaceId(UUID id, UUID userId, UUID workspaceId);

    /** Dépense d'une routine depuis un instant (SF-183-03) — la routine a été relue sous son propriétaire. */
    @Query("select coalesce(sum(r.costUsd), 0) from RoutineRun r where r.routineId = :routineId "
            + "and r.userId = :userId and r.startedAt >= :from")
    java.math.BigDecimal spentSince(@Param("routineId") UUID routineId, @Param("userId") UUID userId,
            @Param("from") OffsetDateTime from);

    /** Les exécutions en cours depuis avant cette limite : un pod mort ne les finira jamais. */
    @Query("select r from RoutineRun r where r.status = fr.claudegateway.atelier.routine.RoutineRunStatus.RUNNING "
            + "and r.startedAt < :before")
    List<RoutineRun> findStaleRunning(@Param("before") OffsetDateTime before);
}
