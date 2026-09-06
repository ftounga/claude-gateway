package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de la configuration de l'alerte de quota (F-42 / SF-42-01). Un seuil mal
 * configuré ne doit jamais rendre l'alerte absurde (alerte dès le premier jeton) ni impossible
 * (seuil supérieur à 100 %) : il retombe sur le défaut.
 */
class QuotaAlertPropertiesTest {

    @Test
    void defaultsToEightyPercentAndStandardPack() {
        QuotaAlertProperties properties = new QuotaAlertProperties(null, null);

        assertThat(properties.threshold()).isEqualTo(0.8d);
        assertThat(properties.thresholdPercent()).isEqualTo(80);
        assertThat(properties.topUpPack()).isEqualTo("STANDARD");
    }

    @Test
    void keepsConfiguredThresholdWithinBounds() {
        assertThat(new QuotaAlertProperties(0.5d, null).threshold()).isEqualTo(0.5d);
        assertThat(new QuotaAlertProperties(0.5d, null).thresholdPercent()).isEqualTo(50);
        // 100 % est légal : « préviens-moi quand le quota est atteint ».
        assertThat(new QuotaAlertProperties(1.0d, null).threshold()).isEqualTo(1.0d);
    }

    @Test
    void fallsBackToDefaultWhenThresholdIsOutOfBounds() {
        assertThat(new QuotaAlertProperties(0d, null).threshold()).isEqualTo(0.8d);
        assertThat(new QuotaAlertProperties(-0.2d, null).threshold()).isEqualTo(0.8d);
        assertThat(new QuotaAlertProperties(1.5d, null).threshold()).isEqualTo(0.8d);
    }

    @Test
    void normalizesPackCodeAndFallsBackWhenBlank() {
        assertThat(new QuotaAlertProperties(null, "  DAY  ").topUpPack()).isEqualTo("DAY");
        assertThat(new QuotaAlertProperties(null, "   ").topUpPack()).isEqualTo("STANDARD");
    }
}
