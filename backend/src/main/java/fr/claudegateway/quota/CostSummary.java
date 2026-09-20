package fr.claudegateway.quota;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Ce que l'administrateur lit sur une période : la dépense réelle, le budget, la part
 * (F-133 / SF-133-07).
 *
 * <p><b>La part est calculée ici, pas dans le navigateur.</b> Elle dépend d'une règle métier — le
 * budget propre, sinon le défaut, sinon aucun — et la recalculer côté écran en ferait une seconde
 * définition, qui divergerait un jour de celle des alertes.</p>
 *
 * @param period    {@code week} ou {@code month}
 * @param from      premier jour observé (inclus)
 * @param to        dernier jour observé (inclus)
 * @param spentEur  dépense totale de la période
 * @param budgetEur somme des budgets applicables, ou {@code null} si aucun client n'est budgété
 * @param percent   part consommée du total, ou {@code null} sans budget
 * @param clients   clients, du plus coûteux au moins ; « hors client » en dernier
 */
public record CostSummary(
        String period,
        LocalDate from,
        LocalDate to,
        BigDecimal spentEur,
        BigDecimal budgetEur,
        Integer percent,
        List<Client> clients) {

    /**
     * Une ligne de l'écran.
     *
     * @param hostId    poste, ou {@code null} pour le seau « hors client »
     * @param hostName  nom du poste, ou {@code null} s'il a été supprimé depuis
     * @param budgetEur budget applicable, ou {@code null} — auquel cas l'écran n'affiche
     *                  <b>aucune</b> part, plutôt qu'une part inventée
     * @param percent   part consommée, ou {@code null} sans budget
     * @param ownBudget {@code true} si ce client a son propre budget, {@code false} s'il hérite du
     *                  défaut — l'écran doit pouvoir proposer de « retirer » l'un et pas l'autre
     */
    public record Client(
            UUID hostId,
            String hostName,
            BigDecimal spentEur,
            BigDecimal budgetEur,
            Integer percent,
            boolean ownBudget,
            long totalTokens) {
    }
}
