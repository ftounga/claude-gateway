package fr.claudegateway.atelier.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** Réglages de dépense des sessions d'Atelier (F-36). */
class AtelierCostPropertiesTest {

    @Test
    void missingValuesFallBackToTheDocumentedDefaults() {
        AtelierCostProperties properties = new AtelierCostProperties(null, null, null, null);

        assertThat(properties.maxRunCost()).isEqualByComparingTo("2.00");
        assertThat(properties.maxRunCostDelegated()).isEqualByComparingTo("5.00");
        assertThat(properties.minRunCost()).isEqualByComparingTo("0.10");
        assertThat(properties.costPerMillionTokens()).isEqualByComparingTo("9.00");
    }

    @Test
    void nonPositiveValuesFallBackToTheDefaultsRatherThanDisablingTheCap() {
        // Un plafond nul ou négatif ouvrirait une session que le fournisseur mettrait aussitôt en
        // pause : on retombe sur le défaut plutôt que de livrer un plafond inutilisable.
        AtelierCostProperties properties = new AtelierCostProperties(
                BigDecimal.ZERO, new BigDecimal("-1"), BigDecimal.ZERO, new BigDecimal("-3"));

        assertThat(properties.maxRunCost()).isEqualByComparingTo("2.00");
        assertThat(properties.maxRunCostDelegated()).isEqualByComparingTo("5.00");
        assertThat(properties.minRunCost()).isEqualByComparingTo("0.10");
        assertThat(properties.costPerMillionTokens()).isEqualByComparingTo("9.00");
    }

    @Test
    void aFloorAboveTheCapIsBroughtBackToTheCap() {
        // Le plafond borne la dépense : un plancher au-dessus le contredirait.
        AtelierCostProperties properties = new AtelierCostProperties(
                new BigDecimal("1.00"), null, new BigDecimal("3.00"), null);

        assertThat(properties.minRunCost()).isEqualByComparingTo("1.00");
    }

    @Test
    void configuredValuesAreKept() {
        AtelierCostProperties properties = new AtelierCostProperties(
                new BigDecimal("4.00"), new BigDecimal("9.00"), new BigDecimal("0.25"),
                new BigDecimal("12.50"));

        assertThat(properties.maxRunCost()).isEqualByComparingTo("4.00");
        assertThat(properties.maxRunCostDelegated()).isEqualByComparingTo("9.00");
        assertThat(properties.minRunCost()).isEqualByComparingTo("0.25");
        assertThat(properties.costPerMillionTokens()).isEqualByComparingTo("12.50");
    }
}
