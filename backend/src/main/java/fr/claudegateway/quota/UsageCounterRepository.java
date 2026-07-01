package fr.claudegateway.quota;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance des compteurs d'usage (F-10). Tout accès propre à un utilisateur passe par une
 * méthode filtrant sur {@code user_id} (isolation multi-tenant). Aucune logique métier ici.
 */
@Repository
public interface UsageCounterRepository extends JpaRepository<UsageCounter, UUID> {

    Optional<UsageCounter> findByUserIdAndPeriodStart(UUID userId, LocalDate periodStart);
}
