package fr.claudegateway.quota;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

/**
 * Convertit la consommation d'un tour en <b>tokens facturés</b> — ce que le quota oppose (F-63).
 *
 * <p>Le quota reste libellé en tokens ; ce qui change, c'est que chaque nature de token y entre
 * <b>au prix de sa nature</b> :</p>
 *
 * <pre>
 * coût du tour ($) = (entrée×Pe + sortie×Ps + lecture_cache×Pc + écriture_cache×Pw) ÷ 1 000 000
 * tokens facturés  = coût × markup ÷ Pq × 1 000 000
 * </pre>
 *
 * <p>Quand le fournisseur rapporte lui-même le coût du tour (Managed Agents,
 * {@code list_cost}), c'est <b>ce</b> coût qui entre dans la seconde ligne : il sait des choses que
 * les tokens ignorent — le modèle réellement servi, les recherches web, le temps de bac à sable.</p>
 *
 * <p><b>Ce que le paramètre {@code Pq} fait, et ce qu'il ne fait pas</b> : il fixe l'échelle du
 * décompte, pas les proportions. Les ratios entre natures viennent des tarifs du fournisseur — un
 * token de sortie pèse cinq tokens d'entrée, une lecture de cache un dixième — et ne dépendent pas
 * de lui. Lui décide combien de coût fournisseur un token de quota représente, donc ce qu'un quota
 * peut coûter au maximum : {@code quota × Pq}, quel que soit le style d'usage du client. C'est
 * exactement ce que F-63 cherche — que la marge cesse de dépendre de la façon dont on se sert du
 * produit.</p>
 *
 * <p><b>Un tour servi n'est jamais gratuit</b> : un coût strictement positif facture au moins un
 * token, faute de quoi une suite d'appels minuscules ne consommerait rien du tout.</p>
 */
@Component
public class BilledTokensCalculator {

    private static final BigDecimal TOKENS_PER_MILLION = BigDecimal.valueOf(1_000_000L);
    /** Précision interne des montants : un tour peut coûter une fraction de centime. */
    private static final int COST_SCALE = 10;

    private final TokenPricingProperties pricing;

    public BilledTokensCalculator(TokenPricingProperties pricing) {
        this.pricing = pricing;
    }

    /**
     * Coût fournisseur d'un tour, en dollars, chaque nature de token à son tarif.
     *
     * @param tokens tokens du tour, par nature (jamais {@code null})
     */
    public BigDecimal costUsd(TurnTokens tokens) {
        return cost(tokens.inputTokens(), pricing.inputCostPerMillionTokens())
                .add(cost(tokens.outputTokens(), pricing.outputCostPerMillionTokens()))
                .add(cost(tokens.cacheReadTokens(), pricing.cacheReadCostPerMillionTokens()))
                .add(cost(tokens.cacheWriteTokens(), pricing.cacheWriteCostPerMillionTokens()));
    }

    /**
     * Tokens facturés d'un tour dont on ne connaît que les tokens : le coût est calculé aux tarifs
     * de configuration, puis converti.
     */
    public long billedTokens(TurnTokens tokens) {
        return billedTokensFromCost(costUsd(tokens));
    }

    /**
     * Tokens facturés d'un tour dont le fournisseur rapporte le coût réel.
     *
     * @param costUsd coût du tour en dollars ; {@code null}, nul ou négatif ⇒ aucun token facturé
     */
    public long billedTokensFromCost(BigDecimal costUsd) {
        if (costUsd == null || costUsd.signum() <= 0) {
            return 0L;
        }
        long billed = costUsd
                .multiply(pricing.markup())
                .multiply(TOKENS_PER_MILLION)
                .divide(pricing.quotaTokenCostPerMillionTokens(), 0, RoundingMode.HALF_UP)
                .longValue();
        return Math.max(1L, billed);
    }

    /**
     * Chemin inverse : ce que <b>vaut</b>, en dollars, un nombre de tokens de quota. Sert au plafond
     * de dépense d'une session d'Atelier (F-36 / SF-36-01), qui borne un run au quota restant de
     * l'utilisateur — déléguer, ou ouvrir une session, ne donne jamais accès à plus que ce qui a été
     * payé.
     *
     * <p>Arrondi <b>vers le bas</b> : mieux vaut un plafond d'un centime trop bas qu'un run qui
     * dépasse le quota.</p>
     *
     * @param quotaTokens tokens de quota (négatif ramené à zéro)
     */
    public BigDecimal usdOfQuotaTokens(long quotaTokens) {
        if (quotaTokens <= 0L) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(quotaTokens)
                .multiply(pricing.quotaTokenCostPerMillionTokens())
                .divide(TOKENS_PER_MILLION, 6, RoundingMode.DOWN);
    }

    private static BigDecimal cost(long tokens, BigDecimal pricePerMillion) {
        if (tokens <= 0L) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(tokens)
                .multiply(pricePerMillion)
                .divide(TOKENS_PER_MILLION, COST_SCALE, RoundingMode.HALF_UP);
    }
}
