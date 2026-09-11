package fr.claudegateway.atelier.agent;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Plafonds de <b>dépense</b> d'une session d'Atelier (F-36). Valeurs commerciales réversibles,
 * ajustables par environnement sans changement de code — jamais un secret.
 *
 * <p>Elles ne servent qu'à une chose : calculer le <b>plafond de dépense</b> posé à la création
 * d'une session (SF-36-01), pour que le pire cas d'un run cesse d'être illimité.</p>
 *
 * <p><b>Ce qui n'est plus ici</b> (F-63) : les tarifs du décompte — ce que vaut un token de quota,
 * le prix de chaque nature de token, le markup — vivent désormais dans
 * {@code fr.claudegateway.quota.TokenPricingProperties}, <b>sous le même préfixe de
 * configuration</b>. Une notion, un seul endroit dans le code ; deux endroits auraient fini par
 * diverger.</p>
 *
 * @param maxRunCost             plafond de dépense d'un run, en dollars (défaut {@code 2.00})
 * @param maxRunCostDelegated    plafond de dépense d'un run avec délégation à des sous-agents, en
 *                               dollars (défaut {@code 5.00}), retenu par
 *                               {@code AtelierSessionService} quand la session ouvre un roster de
 *                               sous-agents (F-35 / SF-35-01). Toujours borné par le quota restant :
 *                               déléguer ne donne jamais accès à plus que ce qui a été payé
 * @param minRunCost             plancher de plafond, en dollars (défaut {@code 0.10}) : un budget nul
 *                               serait refusé par le fournisseur, ou mettrait la session en pause
 *                               immédiatement avec une erreur incompréhensible. Il borne le
 *                               dépassement possible du quota à quelques centimes
 */
@ConfigurationProperties(prefix = "app.atelier.agent.cost")
public record AtelierCostProperties(
        BigDecimal maxRunCost,
        BigDecimal maxRunCostDelegated,
        BigDecimal minRunCost) {

    private static final BigDecimal DEFAULT_MAX_RUN_COST = new BigDecimal("2.00");
    private static final BigDecimal DEFAULT_MAX_RUN_COST_DELEGATED = new BigDecimal("5.00");
    private static final BigDecimal DEFAULT_MIN_RUN_COST = new BigDecimal("0.10");

    public AtelierCostProperties {
        if (maxRunCost == null || maxRunCost.signum() <= 0) {
            maxRunCost = DEFAULT_MAX_RUN_COST;
        }
        if (maxRunCostDelegated == null || maxRunCostDelegated.signum() <= 0) {
            maxRunCostDelegated = DEFAULT_MAX_RUN_COST_DELEGATED;
        }
        if (minRunCost == null || minRunCost.signum() <= 0) {
            minRunCost = DEFAULT_MIN_RUN_COST;
        }
        // Un plancher au-dessus du plafond n'a pas de sens : le plafond gagne (il borne la dépense).
        if (minRunCost.compareTo(maxRunCost) > 0) {
            minRunCost = maxRunCost;
        }
    }
}
