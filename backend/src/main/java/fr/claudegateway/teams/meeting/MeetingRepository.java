package fr.claudegateway.teams.meeting;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Accès aux artefacts réunion (F-128 / SF-128-01).
 *
 * <p>Toutes les recherches sont <b>filtrées par {@code user_id} ET {@code host_id}</b> : aucun finder
 * par {@code id} seul n'est exposé, pour qu'aucun appelant ne puisse contourner l'isolation.</p>
 */
public interface MeetingRepository extends JpaRepository<Meeting, UUID> {

    List<Meeting> findByUserIdAndHostIdOrderByStartedAtDesc(UUID userId, UUID hostId);

    Optional<Meeting> findByIdAndUserIdAndHostId(UUID id, UUID userId, UUID hostId);
}
