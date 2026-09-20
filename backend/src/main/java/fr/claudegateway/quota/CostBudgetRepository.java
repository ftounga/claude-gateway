package fr.claudegateway.quota;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Lectures et écritures des budgets (F-133 / SF-133-04). <b>Toutes</b> filtrent {@code user_id} :
 * il n'existe volontairement aucune méthode qui rende un budget sans dire de qui il est.
 */
public interface CostBudgetRepository extends JpaRepository<CostBudget, UUID> {

    /** Tous les budgets d'un utilisateur, le défaut compris. */
    List<CostBudget> findByUserId(UUID userId);

    /** Le budget d'un client précis. */
    Optional<CostBudget> findByUserIdAndHostId(UUID userId, UUID hostId);

    /** Le budget <b>par défaut</b> ({@code host_id} nul). */
    Optional<CostBudget> findByUserIdAndHostIdIsNull(UUID userId);

    /** Suppression du budget d'un client : il retombe alors sur le défaut. */
    void deleteByUserIdAndHostId(UUID userId, UUID hostId);
}
