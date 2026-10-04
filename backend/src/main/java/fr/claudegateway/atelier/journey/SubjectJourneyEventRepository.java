package fr.claudegateway.atelier.journey;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Le journal du parcours (F-176) — toujours sous {@code user_id}. */
public interface SubjectJourneyEventRepository extends JpaRepository<SubjectJourneyEvent, UUID> {

    long countByUserIdAndWorkspaceIdAndType(UUID userId, UUID workspaceId, String type);

    /** Purge à la suppression du compte. */
    @Modifying
    @Query("delete from SubjectJourneyEvent e where e.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
