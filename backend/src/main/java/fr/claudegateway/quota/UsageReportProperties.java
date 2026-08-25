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
 * <p>Les défauts sont ceux du modèle <b>réellement servi</b> (Opus : 5 / 25 par million,
 * F-36 / SF-36-03). Ils valaient auparavant 3 / 15 — le tarif de Sonnet — et sous-estimaient donc le
 * coût d'environ 40 % : le rapport décrivait un modèle que la plateforme n'utilise pas.</p>
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
    private static final BigDecimal DEFAULT_INPUT_COST = new BigDecimal("5.00");
    private static final BigDecimal DEFAULT_OUTPUT_COST = new BigDecimal("25.00");

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
