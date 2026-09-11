package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** Tarifs du décompte au coût réel (F-63 / SF-63-01) : défauts, repli et surcharge. */
class TokenPricingPropertiesTest {

    @Test
    void missingValuesFallBackToTheDocumentedDefaults() {
        TokenPricingProperties pricing = new TokenPricingProperties(null, null, null, null, null, null);

        // Tarifs du fournisseur (Opus 5), et non des prix de vente.
        assertThat(pricing.inputCostPerMillionTokens()).isEqualByComparingTo("5.00");
        assertThat(pricing.outputCostPerMillionTokens()).isEqualByComparingTo("25.00");
        assertThat(pricing.cacheReadCostPerMillionTokens()).isEqualByComparingTo("0.50");
        assertThat(pricing.cacheWriteCostPerMillionTokens()).isEqualByComparingTo("6.25");
        // Valeur d'un token de quota : exactement celle d'avant F-63 (`cost-per-million-tokens`).
        assertThat(pricing.quotaTokenCostPerMillionTokens()).isEqualByComparingTo("9.00");
        assertThat(pricing.markup()).isEqualByComparingTo("1.0");
    }

    @Test
    void nonPositiveValuesFallBackRatherThanBillingNothingOrDividingByZero() {
        TokenPricingProperties pricing = new TokenPricingProperties(
                BigDecimal.ZERO, new BigDecimal("-1"), BigDecimal.ZERO, new BigDecimal("-2"),
                BigDecimal.ZERO, new BigDecimal("-3"));

        assertThat(pricing.inputCostPerMillionTokens()).isEqualByComparingTo("5.00");
        assertThat(pricing.outputCostPerMillionTokens()).isEqualByComparingTo("25.00");
        assertThat(pricing.cacheReadCostPerMillionTokens()).isEqualByComparingTo("0.50");
        assertThat(pricing.cacheWriteCostPerMillionTokens()).isEqualByComparingTo("6.25");
        assertThat(pricing.quotaTokenCostPerMillionTokens()).isEqualByComparingTo("9.00");
        assertThat(pricing.markup()).isEqualByComparingTo("1.0");
    }

    @Test
    void configuredValuesAreKept() {
        // Le jour où le fournisseur change ses tarifs, c'est ici — en configuration — que cela se
        // règle, jamais dans le code.
        TokenPricingProperties pricing = new TokenPricingProperties(
                new BigDecimal("3.00"), new BigDecimal("15.00"), new BigDecimal("0.30"),
                new BigDecimal("3.75"), new BigDecimal("6.00"), new BigDecimal("1.5"));

        assertThat(pricing.inputCostPerMillionTokens()).isEqualByComparingTo("3.00");
        assertThat(pricing.outputCostPerMillionTokens()).isEqualByComparingTo("15.00");
        assertThat(pricing.cacheReadCostPerMillionTokens()).isEqualByComparingTo("0.30");
        assertThat(pricing.cacheWriteCostPerMillionTokens()).isEqualByComparingTo("3.75");
        assertThat(pricing.quotaTokenCostPerMillionTokens()).isEqualByComparingTo("6.00");
        assertThat(pricing.markup()).isEqualByComparingTo("1.5");
    }
}
