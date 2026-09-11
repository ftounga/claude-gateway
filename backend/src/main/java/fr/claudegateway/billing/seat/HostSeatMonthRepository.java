package fr.claudegateway.billing.seat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance des mois-postes (F-65 / SF-65-01). Toute lecture propre à un utilisateur filtre sur
 * {@code user_id} (isolation multi-tenant). Aucune logique métier ici.
 */
@Repository
public interface HostSeatMonthRepository extends JpaRepository<HostSeatMonth, UUID> {

    /** Mois-postes d'un utilisateur sur une période — la seule lecture de calcul. */
    List<HostSeatMonth> findByUserIdAndPeriodStart(UUID userId, LocalDate periodStart);

    /** Ligne d'un poste sur une période : sa présence est la règle « déjà compté ce mois-ci ». */
    Optional<HostSeatMonth> findByHostIdAndPeriodStart(UUID hostId, LocalDate periodStart);

    /** Purge à la suppression d'un poste : détruire un poste n'est pas le clôturer. */
    void deleteByHostId(UUID hostId);

    /** Purge à la suppression du compte : aucune pièce de facturation ne survit à son propriétaire. */
    void deleteByUserId(UUID userId);
}
