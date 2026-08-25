package fr.claudegateway.atelier.agent;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages de <b>dépense</b> des sessions d'Atelier (F-36). Valeurs commerciales réversibles,
 * ajustables par environnement sans changement de code — jamais un secret.
 *
 * <p>Elles servent à deux choses :</p>
 * <ul>
 *   <li>calculer le <b>plafond de dépense</b> posé à la création d'une session (SF-36-01) : le pire
 *       cas d'un run cesse d'être illimité ;</li>
 *   <li>convertir un montant en dollars en <b>équivalent tokens</b> pour le décompte du quota
 *       (SF-36-02), qui reste libellé en tokens.</li>
 * </ul>
 *
 * @param maxRunCost             plafond de dépense d'un run, en dollars (défaut {@code 2.00})
 * @param maxRunCostDelegated    plafond de dépense d'un run avec délégation à des sous-agents, en
 *                               dollars (défaut {@code 5.00}). <b>Dormant</b> : la délégation (F-35)
 *                               n'est pas livrée, aucun appelant ne le demande encore
 * @param minRunCost             plancher de plafond, en dollars (défaut {@code 0.10}) : un budget nul
 *                               serait refusé par le fournisseur, ou mettrait la session en pause
 *                               immédiatement avec une erreur incompréhensible. Il borne le
 *                               dépassement possible du quota à quelques centimes
 * @param costPerMillionTokens   coût de référence du fournisseur, en dollars par million de tokens
 *                               (défaut {@code 9.00} — blended Opus). Sert à convertir le quota
 *                               restant en dollars, et le coût réel en équivalent tokens
 * @param markup                 multiplicateur appliqué au coût réel lors du décompte du quota
 *                               (F-36 / SF-36-02). Défaut {@code 1.0} = <b>neutre</b> : les
 *                               allocations de tokens configurées par plan embarquent déjà la marge
 *                               commerciale (GOLD : 199 € pour 12 M tokens ≈ 2× le coût blended).
 *                               Le porter à 2,0 doublerait la vitesse de consommation du quota de
 *                               chaque client — c'est le levier de marge, à actionner sciemment
 */
@ConfigurationProperties(prefix = "app.atelier.agent.cost")
public record AtelierCostProperties(
        BigDecimal maxRunCost,
        BigDecimal maxRunCostDelegated,
        BigDecimal minRunCost,
        BigDecimal costPerMillionTokens,
        BigDecimal markup) {

    private static final BigDecimal DEFAULT_MAX_RUN_COST = new BigDecimal("2.00");
    private static final BigDecimal DEFAULT_MAX_RUN_COST_DELEGATED = new BigDecimal("5.00");
    private static final BigDecimal DEFAULT_MIN_RUN_COST = new BigDecimal("0.10");
    private static final BigDecimal DEFAULT_COST_PER_MILLION_TOKENS = new BigDecimal("9.00");
    private static final BigDecimal DEFAULT_MARKUP = BigDecimal.ONE;

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
        if (costPerMillionTokens == null || costPerMillionTokens.signum() <= 0) {
            costPerMillionTokens = DEFAULT_COST_PER_MILLION_TOKENS;
        }
        if (markup == null || markup.signum() <= 0) {
            markup = DEFAULT_MARKUP;
        }
        // Un plancher au-dessus du plafond n'a pas de sens : le plafond gagne (il borne la dépense).
        if (minRunCost.compareTo(maxRunCost) > 0) {
            minRunCost = maxRunCost;
        }
    }
}
