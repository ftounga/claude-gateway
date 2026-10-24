package fr.claudegateway.atelier.journey;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Les versions du plan des chantiers (F-208 / SF-208-02) — toujours sous {@code user_id}. */
public interface SubjectJourneyPlanVersionRepository extends JpaRepository<SubjectJourneyPlanVersion, UUID> {

    /** Les versions d'un chantier, de la plus ancienne à la plus récente. */
    List<SubjectJourneyPlanVersion> findByUserIdAndWorkspaceIdAndChantierNumberOrderByVersionAscCreatedAtAsc(
            UUID userId, UUID workspaceId, int chantierNumber);

    /** Purge à la suppression du compte. */
    @Modifying
    @Query("delete from SubjectJourneyPlanVersion v where v.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
