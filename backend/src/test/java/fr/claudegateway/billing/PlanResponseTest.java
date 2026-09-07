package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import fr.claudegateway.billing.dto.PlanResponse;

/**
 * F-43 / SF-43-01 — projection d'un plan vers l'API : l'engagement annuel est porté quand il est
 * proposé, et le quota reste l'allocation <b>mensuelle</b>.
 */
class PlanResponseTest {

    private static final Plan SOLO =
            new Plan(PlanCode.SOLO, "Solo", ProviderMode.HOSTED, BillingPeriod.MONTHLY);

    @Test
    void carriesYearlyPriceWhenTheYearlyOfferIsAvailable() {
        PlanResponse response = PlanResponse.of(SOLO, 1_000_000L, "24", "240", true);

        assertThat(response.code()).isEqualTo("SOLO");
        assertThat(response.period()).isEqualTo("MONTHLY");
        assertThat(response.priceEur()).isEqualTo("24");
        assertThat(response.yearlyPriceEur()).isEqualTo("240");
        assertThat(response.yearlyAvailable()).isTrue();
    }

    @Test
    void hidesYearlyPriceWhenTheYearlyOfferIsNotAvailable() {
        // Un montant annuel configuré mais sans price payable ne doit pas atteindre l'écran :
        // afficher un prix qu'on ne peut pas payer est pire que ne rien afficher.
        PlanResponse response = PlanResponse.of(SOLO, 1_000_000L, "24", "240", false);

        assertThat(response.yearlyPriceEur()).isNull();
        assertThat(response.yearlyAvailable()).isFalse();
    }

    @Test
    void tokensRemainTheMonthlyAllocationWhateverTheYearlyOffer() {
        // LA règle de F-43 : l'engagement est annuel, l'allocation reste mensuelle. La projection
        // ne multiplie rien par douze — un abonné annuel ne reçoit pas douze mois de jetons d'un coup.
        long monthlyTokens = 1_000_000L;

        PlanResponse monthlyOnly = PlanResponse.of(SOLO, monthlyTokens, "24", null, false);
        PlanResponse withYearly = PlanResponse.of(SOLO, monthlyTokens, "24", "240", true);

        assertThat(monthlyOnly.tokens()).isEqualTo(monthlyTokens);
        assertThat(withYearly.tokens()).isEqualTo(monthlyTokens);
    }
}
