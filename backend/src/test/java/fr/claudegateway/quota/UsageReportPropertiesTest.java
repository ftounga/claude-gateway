package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** Configuration du rapport d'usage &amp; coût (F-16), tarifs corrigés en F-36 / SF-36-03. */
class UsageReportPropertiesTest {

    @Test
    void defaultRatesAreThoseOfTheModelActuallyServed() {
        // Opus (5 / 25 par million), et non Sonnet (3 / 15) : le rapport décrivait un modèle que la
        // plateforme n'utilise pas, sous-estimant le coût d'environ 40 %.
        UsageReportProperties properties = new UsageReportProperties(null, null, null, null);

        assertThat(properties.inputCostPerMillionTokens()).isEqualByComparingTo("5.00");
        assertThat(properties.outputCostPerMillionTokens()).isEqualByComparingTo("25.00");
        assertThat(properties.currency()).isEqualTo("EUR");
        assertThat(properties.maxMonths()).isEqualTo(12);
    }

    @Test
    void configuredRatesWinOverTheDefaults() {
        UsageReportProperties properties = new UsageReportProperties(
                "USD", 6, new BigDecimal("4.00"), new BigDecimal("20.00"));

        assertThat(properties.inputCostPerMillionTokens()).isEqualByComparingTo("4.00");
        assertThat(properties.outputCostPerMillionTokens()).isEqualByComparingTo("20.00");
        assertThat(properties.currency()).isEqualTo("USD");
        assertThat(properties.maxMonths()).isEqualTo(6);
    }

    @Test
    void negativeRatesFallBackToTheDefaults() {
        UsageReportProperties properties = new UsageReportProperties(
                "  ", 0, new BigDecimal("-1"), new BigDecimal("-2"));

        assertThat(properties.inputCostPerMillionTokens()).isEqualByComparingTo("5.00");
        assertThat(properties.outputCostPerMillionTokens()).isEqualByComparingTo("25.00");
        assertThat(properties.currency()).isEqualTo("EUR");
        assertThat(properties.maxMonths()).isEqualTo(12);
    }
}
