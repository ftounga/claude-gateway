package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * Tests de l'estimateur de coût partagé (F-61) : la formule qui vivait en privé dans le rapport
 * F-16 rend exactement les mêmes montants, et entrée et sortie ne sont jamais confondues.
 */
class UsageCostEstimatorTest {

    private final UsageReportProperties properties =
            new UsageReportProperties("EUR", 12, new BigDecimal("3.00"), new BigDecimal("15.00"));
    private final UsageCostEstimator estimator = new UsageCostEstimator(properties);

    @Test
    void inputAndOutputArePricedSeparately() {
        // 1 M d'entrée à 3 € + 1 M de sortie à 15 € = 18 €. Un tarif « moyen » appliqué aux 2 M
        // donnerait 18 € aussi — d'où un second cas, dissymétrique, qui le démasque.
        assertThat(estimator.estimate(1_000_000L, 1_000_000L)).isEqualByComparingTo("18.0000");
    }

    @Test
    void asymmetricUsageIsNotPricedAtABlendedRate() {
        // 2 M d'entrée et 0 de sortie : 6 €. Au tarif moyen (9 €/M) on lirait 18 € — le triple.
        assertThat(estimator.estimate(2_000_000L, 0L)).isEqualByComparingTo("6.0000");
    }

    @Test
    void defaultsArePricedAtTheModelActuallyServed() {
        // F-36 / SF-36-03 : sans tarif configuré, 5 / 25 (Opus), pas 3 / 15 (Sonnet).
        UsageCostEstimator atDefaults =
                new UsageCostEstimator(new UsageReportProperties("EUR", 12, null, null));

        assertThat(atDefaults.estimate(1_000L, 2_000L)).isEqualByComparingTo("0.0550");
    }

    @Test
    void negativeVolumesAreTreatedAsZero() {
        assertThat(estimator.estimate(-1_000L, -2_000L)).isEqualByComparingTo("0.0000");
    }

    @Test
    void currencyComesFromConfiguration() {
        assertThat(estimator.currency()).isEqualTo("EUR");
    }

    @Test
    void shareIsZeroWhenTotalIsZero() {
        // Une part de rien n'est pas une erreur : c'est zéro, et surtout pas une division par zéro.
        assertThat(UsageCostEstimator.share(0L, 0L)).isEqualByComparingTo("0");
    }

    @Test
    void shareIsTheFractionOfTheTotal() {
        assertThat(UsageCostEstimator.share(250L, 1_000L)).isEqualByComparingTo("0.2500");
    }
}
