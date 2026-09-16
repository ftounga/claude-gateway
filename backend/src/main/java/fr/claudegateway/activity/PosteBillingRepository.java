package fr.claudegateway.activity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance des TJM par poste (F-124 / SF-124-01). Toute lecture filtre {@code user_id} : le TJM
 * d'un poste n'est visible que par son propriétaire. Aucune logique métier ici.
 */
@Repository
public interface PosteBillingRepository extends JpaRepository<PosteBilling, UUID> {

    /** Le TJM d'un poste, pour son propriétaire. */
    Optional<PosteBilling> findByUserIdAndHostId(UUID userId, UUID hostId);

    /** Tous les TJM d'un utilisateur. */
    List<PosteBilling> findByUserId(UUID userId);
}
