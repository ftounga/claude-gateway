package fr.claudegateway.atelier.journey;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Les chantiers d'un sujet, clos (F-176 / SF-176-11) ou ouverts (F-206) — toujours sous {@code user_id}. */
public interface SubjectJourneyChantierRepository extends JpaRepository<SubjectJourneyChantier, UUID> {

    /** Les chantiers d'un statut ({@code OPEN} ou {@code CLOSED}), le plus récent d'abord. */
    List<SubjectJourneyChantier> findByUserIdAndWorkspaceIdAndStatusOrderByNumberDesc(UUID userId, UUID workspaceId,
            String status);

    /** Les chantiers d'un statut, par numéro croissant (F-206). */
    List<SubjectJourneyChantier> findByUserIdAndWorkspaceIdAndStatusOrderByNumberAsc(UUID userId, UUID workspaceId,
            String status);

    long countByUserIdAndWorkspaceIdAndStatus(UUID userId, UUID workspaceId, String status);

    /** Le chantier d'un numéro, quel que soit son statut (F-206). */
    java.util.Optional<SubjectJourneyChantier> findFirstByUserIdAndWorkspaceIdAndNumber(UUID userId, UUID workspaceId,
            int number);

    /** Le plus grand numéro de chantier du sujet, 0 s'il n'y en a aucun (F-206). */
    @Query("select coalesce(max(c.number), 0) from SubjectJourneyChantier c "
            + "where c.userId = :userId and c.workspaceId = :workspaceId")
    int maxNumber(@Param("userId") UUID userId, @Param("workspaceId") UUID workspaceId);

    /** Purge à la suppression du compte. */
    @Modifying
    @Query("delete from SubjectJourneyChantier c where c.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
