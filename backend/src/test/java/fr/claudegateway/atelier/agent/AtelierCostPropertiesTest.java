package fr.claudegateway.atelier.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * Plafonds de dépense des sessions d'Atelier (F-36).
 *
 * <p>Depuis F-63, les tarifs du décompte ne vivent plus ici : ce que vaut un token de quota, le
 * prix de chaque nature de token et le markup sont dans
 * {@code fr.claudegateway.quota.TokenPricingProperties}, sous le même préfixe de configuration.</p>
 */
class AtelierCostPropertiesTest {

    @Test
    void missingValuesFallBackToTheDocumentedDefaults() {
        AtelierCostProperties properties = new AtelierCostProperties(null, null, null);

        assertThat(properties.maxRunCost()).isEqualByComparingTo("2.00");
        assertThat(properties.maxRunCostDelegated()).isEqualByComparingTo("5.00");
        assertThat(properties.minRunCost()).isEqualByComparingTo("0.10");
    }

    @Test
    void nonPositiveValuesFallBackToTheDefaultsRatherThanDisablingTheCap() {
        // Un plafond nul ou négatif ouvrirait une session que le fournisseur mettrait aussitôt en
        // pause : on retombe sur le défaut plutôt que de livrer un plafond inutilisable.
        AtelierCostProperties properties = new AtelierCostProperties(
                BigDecimal.ZERO, new BigDecimal("-1"), BigDecimal.ZERO);

        assertThat(properties.maxRunCost()).isEqualByComparingTo("2.00");
        assertThat(properties.maxRunCostDelegated()).isEqualByComparingTo("5.00");
        assertThat(properties.minRunCost()).isEqualByComparingTo("0.10");
    }

    @Test
    void aFloorAboveTheCapIsBroughtBackToTheCap() {
        // Le plafond borne la dépense : un plancher au-dessus le contredirait.
        AtelierCostProperties properties = new AtelierCostProperties(
                new BigDecimal("1.00"), null, new BigDecimal("3.00"));

        assertThat(properties.minRunCost()).isEqualByComparingTo("1.00");
    }

    @Test
    void configuredValuesAreKept() {
        AtelierCostProperties properties = new AtelierCostProperties(
                new BigDecimal("4.00"), new BigDecimal("9.00"), new BigDecimal("0.25"));

        assertThat(properties.maxRunCost()).isEqualByComparingTo("4.00");
        assertThat(properties.maxRunCostDelegated()).isEqualByComparingTo("9.00");
        assertThat(properties.minRunCost()).isEqualByComparingTo("0.25");
    }
}
