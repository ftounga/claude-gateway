package fr.claudegateway.quota;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration du rapport d'usage &amp; coût (F-16). Toutes les valeurs sont externalisées et
 * réversibles (ajustables par environnement sans changement de code) : elles décrivent une
 * structure commerciale (devise, fenêtre d'historique, tarifs estimés), jamais un secret.
 *
 * <p>Le coût est une <b>estimation</b> : les compteurs F-10 agrègent les tokens d'entrée/sortie
 * sans ventilation par modèle. Le tarif « blended » configuré ci-dessous est donc appliqué à
 * l'ensemble de la période.</p>
 *
 * @param currency                     devise d'affichage du coût estimé (ex. {@code EUR})
 * @param maxMonths                    nombre maximum de périodes retournées (les plus récentes)
 * @param inputCostPerMillionTokens    prix estimé par million de tokens d'entrée
 * @param outputCostPerMillionTokens   prix estimé par million de tokens de sortie
 */
@ConfigurationProperties(prefix = "app.usage.report")
public record UsageReportProperties(
        String currency,
        Integer maxMonths,
        BigDecimal inputCostPerMillionTokens,
        BigDecimal outputCostPerMillionTokens) {

    private static final String DEFAULT_CURRENCY = "EUR";
    private static final int DEFAULT_MAX_MONTHS = 12;
    private static final BigDecimal DEFAULT_INPUT_COST = new BigDecimal("3.00");
    private static final BigDecimal DEFAULT_OUTPUT_COST = new BigDecimal("15.00");

    public UsageReportProperties {
        if (currency == null || currency.isBlank()) {
            currency = DEFAULT_CURRENCY;
        }
        if (maxMonths == null || maxMonths < 1) {
            maxMonths = DEFAULT_MAX_MONTHS;
        }
        if (inputCostPerMillionTokens == null || inputCostPerMillionTokens.signum() < 0) {
            inputCostPerMillionTokens = DEFAULT_INPUT_COST;
        }
        if (outputCostPerMillionTokens == null || outputCostPerMillionTokens.signum() < 0) {
            outputCostPerMillionTokens = DEFAULT_OUTPUT_COST;
        }
    }
}
