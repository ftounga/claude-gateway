package fr.claudegateway.atelier.routine;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux routines — toujours sous {@code user_id} + {@code workspace_id} (F-183 / SF-183-01). */
public interface SubjectRoutineRepository extends JpaRepository<SubjectRoutine, UUID> {

    List<SubjectRoutine> findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(UUID userId, UUID workspaceId);

    Optional<SubjectRoutine> findByIdAndUserIdAndWorkspaceId(UUID id, UUID userId, UUID workspaceId);

    long countByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);

    /** Purge à la suppression du compte. */
    @Modifying
    @Query("delete from SubjectRoutine r where r.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
