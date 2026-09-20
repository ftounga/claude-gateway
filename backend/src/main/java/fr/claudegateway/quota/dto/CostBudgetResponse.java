package fr.claudegateway.quota.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import fr.claudegateway.quota.CostBudget;

/**
 * Les budgets hebdomadaires tels que l'écran les lit (F-133 / SF-133-04).
 *
 * <p>DTO <b>distinct</b> de la requête : celle-ci ne porte qu'un montant, celle-là rend aussi qui,
 * quand et pour quel client.</p>
 *
 * @param defaultAmountEur budget par défaut, ou {@code null} s'il n'y en a pas
 * @param hosts            budgets propres à un client
 */
public record CostBudgetResponse(BigDecimal defaultAmountEur, List<HostBudget> hosts) {

    /**
     * Budget d'un client.
     *
     * @param hostId    poste concerné
     * @param amountEur montant hebdomadaire
     * @param updatedAt dernière modification
     */
    public record HostBudget(UUID hostId, BigDecimal amountEur, OffsetDateTime updatedAt) {

        static HostBudget from(CostBudget budget) {
            return new HostBudget(budget.getHostId(), budget.getAmountEur(), budget.getUpdatedAt());
        }
    }

    /** Projette la liste brute : le défaut d'un côté, les clients de l'autre. */
    public static CostBudgetResponse from(List<CostBudget> budgets) {
        BigDecimal defaultAmount = budgets.stream()
                .filter(budget -> budget.getHostId() == null)
                .map(CostBudget::getAmountEur)
                .findFirst()
                .orElse(null);
        List<HostBudget> hosts = budgets.stream()
                .filter(budget -> budget.getHostId() != null)
                .map(HostBudget::from)
                .toList();
        return new CostBudgetResponse(defaultAmount, hosts);
    }
}
