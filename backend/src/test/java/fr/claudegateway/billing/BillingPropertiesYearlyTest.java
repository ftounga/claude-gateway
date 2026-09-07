package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * F-43 / SF-43-01 — configuration de l'engagement annuel : résolution des price IDs et des montants
 * d'affichage annuels, et surtout la règle qui décide si une offre annuelle est réellement
 * proposable.
 */
class BillingPropertiesYearlyTest {

    private static final Plan SOLO =
            new Plan(PlanCode.SOLO, "Solo", ProviderMode.HOSTED, BillingPeriod.MONTHLY);
    private static final Plan DAILY_PASS =
            new Plan(PlanCode.DAILY, "Pass journée", ProviderMode.HOSTED, BillingPeriod.DAILY);

    private static BillingProperties.Stripe stripe(
            Map<String, String> yearlyPrices, Map<String, String> yearlyDisplayPrices) {
        return new BillingProperties.Stripe(
                "sk_test", "whsec_test",
                Map.of("SOLO", "price_solo", "DAILY", "price_daily"),
                Map.of(), null, null,
                Map.of("SOLO", "24", "DAILY", "9"),
                null, null,
                yearlyPrices, yearlyDisplayPrices);
    }

    @Test
    void resolvesConfiguredYearlyPriceAndDisplayPrice() {
        BillingProperties.Stripe config =
                stripe(Map.of("SOLO", "price_solo_yearly"), Map.of("SOLO", "240"));

        assertThat(config.yearlyPriceId(PlanCode.SOLO)).isEqualTo("price_solo_yearly");
        assertThat(config.yearlyDisplayPrice(PlanCode.SOLO)).isEqualTo("240");
    }

    @Test
    void returnsNullForPlanWithoutYearlyConfiguration() {
        BillingProperties.Stripe config =
                stripe(Map.of("SOLO", "price_solo_yearly"), Map.of("SOLO", "240"));

        assertThat(config.yearlyPriceId(PlanCode.PRO)).isNull();
        assertThat(config.yearlyDisplayPrice(PlanCode.PRO)).isNull();
    }

    @Test
    void toleratesNullMappingsAndNullPlanCode() {
        BillingProperties.Stripe config = stripe(null, null);

        assertThat(config.yearlyPrices()).isEmpty();
        assertThat(config.yearlyDisplayPrices()).isEmpty();
        assertThat(config.yearlyPriceId(null)).isNull();
        assertThat(config.yearlyDisplayPrice(null)).isNull();
        assertThat(config.isYearlyAvailable(SOLO)).isFalse();
        assertThat(config.isYearlyAvailable(null)).isFalse();
    }

    @Test
    void yearlyIsAvailableOnlyWhenPriceAndDisplayPriceAreBothConfigured() {
        assertThat(stripe(Map.of("SOLO", "price_solo_yearly"), Map.of("SOLO", "240"))
                .isYearlyAvailable(SOLO)).isTrue();
    }

    @Test
    void yearlyIsUnavailableWhenDisplayPriceIsMissing() {
        // Un price sans montant : un bouton « Payer à l'année » sans prix affiché.
        assertThat(stripe(Map.of("SOLO", "price_solo_yearly"), Map.of()).isYearlyAvailable(SOLO))
                .isFalse();
        assertThat(stripe(Map.of("SOLO", "price_solo_yearly"), Map.of("SOLO", "  "))
                .isYearlyAvailable(SOLO)).isFalse();
    }

    @Test
    void yearlyIsUnavailableWhenPriceIsMissing() {
        // Un montant sans price : un prix affiché qu'on ne peut pas payer (503 au clic).
        assertThat(stripe(Map.of(), Map.of("SOLO", "240")).isYearlyAvailable(SOLO)).isFalse();
        assertThat(stripe(Map.of("SOLO", "  "), Map.of("SOLO", "240")).isYearlyAvailable(SOLO))
                .isFalse();
    }

    @Test
    void dailyPassIsNeverAnnualizedEvenWhenFullyConfigured() {
        // Un pass journée est un paiement UNIQUE : l'annualiser n'a aucun sens. La règle se lit sur
        // la période native du plan, et une configuration accidentelle ne crée pas l'offre.
        BillingProperties.Stripe config =
                stripe(Map.of("DAILY", "price_daily_yearly"), Map.of("DAILY", "500"));

        assertThat(config.isYearlyAvailable(DAILY_PASS)).isFalse();
    }

    @Test
    void dailyPassNowHasADisplayPrice() {
        // Anomalie corrigée par F-43 : le pass journée avait un quota et un price, mais aucun prix
        // d'affichage — l'écran n'avait rien à montrer à côté de son bouton d'achat.
        assertThat(stripe(Map.of(), Map.of()).displayPrice(PlanCode.DAILY)).isEqualTo("9");
    }

    @Test
    void monthlyConfigurationIsUntouchedByYearlyConfiguration() {
        // Non-régression : ajouter l'annuel ne déplace pas d'un iota la résolution mensuelle.
        BillingProperties.Stripe config =
                stripe(Map.of("SOLO", "price_solo_yearly"), Map.of("SOLO", "240"));

        assertThat(config.priceId(PlanCode.SOLO)).isEqualTo("price_solo");
        assertThat(config.displayPrice(PlanCode.SOLO)).isEqualTo("24");
    }
}
