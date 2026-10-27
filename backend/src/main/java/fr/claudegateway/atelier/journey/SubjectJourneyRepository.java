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

    /** Les parcours de plusieurs sujets d'un même utilisateur (F-178 / SF-178-02, {@code sujets_etat}). */
    java.util.List<SubjectJourney> findByUserIdAndWorkspaceIdIn(UUID userId, java.util.Collection<UUID> workspaceIds);

    /**
     * Les parcours d'un mode sans mise à jour depuis {@code before} — tous comptes : le rappel des chantiers
     * dormants (F-220 / SF-220-05), tâche de fond dont chaque émission porte le {@code user_id} de la ligne.
     */
    java.util.List<SubjectJourney> findByModeAndUpdatedAtBefore(JourneyMode mode, java.time.OffsetDateTime before);

    /** Purge à la suppression du compte. */
    @Modifying
    @Query("delete from SubjectJourney j where j.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
