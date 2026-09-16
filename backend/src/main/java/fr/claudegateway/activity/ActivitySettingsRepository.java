package fr.claudegateway.activity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance du réglage de suivi d'activité (F-124 / SF-124-01) : au plus une ligne par
 * {@code user_id}. Aucune logique métier ici.
 */
@Repository
public interface ActivitySettingsRepository extends JpaRepository<ActivitySettings, UUID> {

    /** Le réglage d'un utilisateur, s'il en a un. */
    Optional<ActivitySettings> findByUserId(UUID userId);
}
