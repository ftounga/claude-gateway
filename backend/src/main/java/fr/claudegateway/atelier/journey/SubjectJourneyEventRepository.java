package fr.claudegateway.atelier.journey;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Le journal du parcours (F-176) — toujours sous {@code user_id}. */
public interface SubjectJourneyEventRepository extends JpaRepository<SubjectJourneyEvent, UUID> {

    long countByUserIdAndWorkspaceIdAndType(UUID userId, UUID workspaceId, String type);

    /** Les derniers événements d'un type depuis une date, du plus récent au plus ancien (F-274 / SF-274-02). */
    java.util.List<SubjectJourneyEvent> findTop50ByUserIdAndWorkspaceIdAndTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            UUID userId, UUID workspaceId, String type, java.time.OffsetDateTime after);

    /** Purge à la suppression du compte. */
    @Modifying
    @Query("delete from SubjectJourneyEvent e where e.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
