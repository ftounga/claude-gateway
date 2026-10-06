package fr.claudegateway.atelier.journey;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Les chantiers clos d'un sujet (F-176 / SF-176-11) — toujours sous {@code user_id}. */
public interface SubjectJourneyChantierRepository extends JpaRepository<SubjectJourneyChantier, UUID> {

    List<SubjectJourneyChantier> findByUserIdAndWorkspaceIdOrderByNumberDesc(UUID userId, UUID workspaceId);

    long countByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);

    boolean existsByUserIdAndWorkspaceIdAndNumber(UUID userId, UUID workspaceId, int number);

    /** Purge à la suppression du compte. */
    @Modifying
    @Query("delete from SubjectJourneyChantier c where c.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
