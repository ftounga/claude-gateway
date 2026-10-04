package fr.claudegateway.atelier.journey;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux parcours — toujours sous {@code user_id} (F-176). */
public interface SubjectJourneyRepository extends JpaRepository<SubjectJourney, UUID> {

    Optional<SubjectJourney> findByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);

    /** Purge à la suppression du compte. */
    @Modifying
    @Query("delete from SubjectJourney j where j.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
