package fr.claudegateway.quota;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Le coût <b>réel</b> d'un tour, aux tarifs du fournisseur et du modèle servi (F-133 / SF-133-01).
 *
 * <p><b>Ne pas confondre avec {@link BilledTokensCalculator}</b>, qui répond à une autre question :
 * combien de tokens de quota ce tour consomme-t-il chez le client. Celui-là facture, celui-ci
 * mesure. Ils partent des mêmes tokens et arrivent volontairement à des montants différents — voir
 * {@link ProviderPricingProperties} pour l'écart assumé sur l'écriture de cache.</p>
 *
 * <pre>
 * coût ($) = (entrée×Pe + sortie×Ps + lecture_cache×Pc + écriture_cache×Pw) ÷ 1 000 000
 * </pre>
 *
 * <p>Chaque nature à son tarif, et le tarif du <b>modèle servi</b> : à volume égal, un tour d'Opus 5
 * coûte cinq fois un tour de Haiku 4.5.</p>
 *
 * <p><b>Un problème de tarif ne fait jamais échouer un tour.</b> Le fournisseur a déjà été appelé et
 * payé quand ce calcul s'exécute. Un modèle absent de la grille retombe donc sur les tarifs de
 * repli, la ligne est marquée {@code pricingFallback}, et un avertissement est émis <b>une seule
 * fois par modèle</b> — un tour par seconde ne doit pas produire un journal par seconde.</p>
 */
@Component
public class ProviderCostCalculator {

    private static final Logger log = LoggerFactory.getLogger(ProviderCostCalculator.class);

    private static final BigDecimal TOKENS_PER_MILLION = BigDecimal.valueOf(1_000_000L);

    /**
     * Six décimales : un tour minuscule coûte une fraction de centime, et des milliers de tours
     * s'additionnent. Arrondir plus tôt ferait disparaître la consommation des petits chemins.
     */
    public static final int COST_SCALE = 6;

    /** Modèles déjà signalés comme absents de la grille : on ne prévient qu'une fois. */
    private final Set<String> warnedModels = ConcurrentHashMap.newKeySet();

    private final ProviderPricingProperties pricing;

    public ProviderCostCalculator(ProviderPricingProperties pricing) {
        this.pricing = pricing;
    }

    /**
     * Coût d'un tour dont on connaît les tokens.
     *
     * @param tokens tokens du tour, par nature (jamais {@code null})
     * @param model  modèle servi, ou {@code null} s'il n'a pas été rapporté
     */
    public TurnCost calculate(TurnTokens tokens, String model) {
        ProviderPricingProperties.ModelPricing rates = pricing.pricingOf(model);
        boolean fallback = rates == null;
        if (fallback) {
            rates = pricing.fallbackPricing();
            warnOnce(model);
        }
        BigDecimal amount = cost(tokens.inputTokens(), rates.input())
                .add(cost(tokens.outputTokens(), rates.output()))
                .add(cost(tokens.cacheReadTokens(), rates.cacheRead()))
                .add(cost(tokens.cacheWriteTokens(), rates.cacheWrite()))
                .setScale(COST_SCALE, RoundingMode.HALF_UP);
        return new TurnCost(amount, TurnCost.Source.CALCULATED, model, pricing.pricingVersion(),
                fallback);
    }

    /**
     * Coût d'un tour dont le fournisseur rapporte lui-même le montant. <b>Il fait foi</b> : il sait
     * des choses que les tokens ignorent — le modèle réellement servi, les recherches web, le temps
     * de bac à sable.
     *
     * @param providerCostUsd montant rapporté ; {@code null}, nul ou négatif ⇒ on retombe sur le
     *                        calcul par tokens, car un tour servi n'est jamais gratuit
     * @param tokens          tokens du tour, pour ce repli
     * @param model           modèle servi, ou {@code null}
     */
    public TurnCost calculate(BigDecimal providerCostUsd, TurnTokens tokens, String model) {
        if (providerCostUsd == null || providerCostUsd.signum() <= 0) {
            return calculate(tokens, model);
        }
        return new TurnCost(providerCostUsd.setScale(COST_SCALE, RoundingMode.HALF_UP),
                TurnCost.Source.PROVIDER, model, pricing.pricingVersion(), false);
    }

    private void warnOnce(String model) {
        String key = model == null || model.isBlank() ? "(non rapporté)" : model.trim();
        if (warnedModels.add(key)) {
            log.warn("Modèle {} absent de la grille de tarifs {} : coût estimé au tarif de repli"
                    + " ({}). Relever la grille officielle et compléter app.cost.provider.models.",
                    key, pricing.pricingVersion(), pricing.defaultModel());
        }
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
