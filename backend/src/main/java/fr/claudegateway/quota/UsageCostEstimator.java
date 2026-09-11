package fr.claudegateway.quota;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

/**
 * Estimation du coût d'une consommation, <b>au même tarif pour tous les écrans</b> (F-61).
 *
 * <p>La formule vivait en privé dans {@link UsageReportService} (F-16). Trois écrans la demandent
 * désormais — le rapport mensuel, la consommation par client, la console d'administration — et deux
 * définitions du coût finiraient par diverger. Le jour où elles divergent, deux écrans du même
 * produit annoncent deux montants différents pour la même consommation, et plus personne ne sait
 * lequel croire.</p>
 *
 * <p><b>Entrée et sortie ne sont jamais confondues</b> : chacune est tarifée à son prix
 * (par défaut 5 €/M et 25 €/M — le modèle réellement servi, F-36 / SF-36-03), puis les deux
 * montants sont additionnés. Appliquer un tarif moyen au total se tromperait d'autant plus que la
 * conversation produit de texte.</p>
 *
 * <p>Le résultat est une <b>estimation</b>, jamais un montant facturé : les compteurs agrègent les
 * tokens sans ventilation par modèle.</p>
 */
@Component
public class UsageCostEstimator {

    /** Les tarifs sont exprimés par million de tokens. */
    private static final BigDecimal TOKENS_PER_MILLION = BigDecimal.valueOf(1_000_000L);

    /** Précision monétaire : les montants unitaires sont potentiellement très faibles. */
    public static final int COST_SCALE = 4;

    private final UsageReportProperties properties;

    public UsageCostEstimator(UsageReportProperties properties) {
        this.properties = properties;
    }

    /** Devise d'affichage configurée (ex. {@code EUR}). */
    public String currency() {
        return properties.currency();
    }

    /**
     * Coût estimé = entrée/1e6 × prix_entrée + sortie/1e6 × prix_sortie, arrondi à
     * {@value #COST_SCALE} décimales (HALF_UP).
     *
     * @param inputTokens  tokens d'entrée (négatif ramené à 0)
     * @param outputTokens tokens de sortie (négatif ramené à 0)
     */
    public BigDecimal estimate(long inputTokens, long outputTokens) {
        BigDecimal inputCost = BigDecimal.valueOf(Math.max(0L, inputTokens))
                .multiply(properties.inputCostPerMillionTokens())
                .divide(TOKENS_PER_MILLION, COST_SCALE, RoundingMode.HALF_UP);
        BigDecimal outputCost = BigDecimal.valueOf(Math.max(0L, outputTokens))
                .multiply(properties.outputCostPerMillionTokens())
                .divide(TOKENS_PER_MILLION, COST_SCALE, RoundingMode.HALF_UP);
        return inputCost.add(outputCost);
    }

    /**
     * Part d'un total, entre 0 et 1, arrondie à quatre décimales. Rend {@code 0} quand le total est
     * nul : une part de rien n'est pas une erreur, c'est zéro.
     */
    public static BigDecimal share(long part, long total) {
        if (total <= 0L || part <= 0L) {
            return BigDecimal.ZERO.setScale(COST_SCALE);
        }
        return BigDecimal.valueOf(part)
                .divide(BigDecimal.valueOf(total), COST_SCALE, RoundingMode.HALF_UP);
    }
}
