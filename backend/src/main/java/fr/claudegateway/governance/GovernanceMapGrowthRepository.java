package fr.claudegateway.governance;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Ce que la carte d'un poste a gagné (F-93 / SF-93-02).
 *
 * <p><b>Isolation.</b> Toutes les lectures portent {@code user_id} : il n'existe aucune méthode qui
 * cherche par poste seul.</p>
 */
public interface GovernanceMapGrowthRepository extends JpaRepository<GovernanceMapGrowth, UUID> {

    /** Les lignes d'un poste, pour un utilisateur. */
    List<GovernanceMapGrowth> findByUserIdAndHostId(UUID userId, UUID hostId);
}
