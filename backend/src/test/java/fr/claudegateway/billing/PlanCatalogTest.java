package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests unitaires du catalogue de plans (SF-09-01). */
class PlanCatalogTest {

    private final PlanCatalog catalog = new PlanCatalog();

    @Test
    void exposesSoloProAndDailyPlans() {
        assertThat(catalog.plans())
                .extracting(Plan::code)
                .containsExactlyInAnyOrder(PlanCode.SOLO, PlanCode.PRO, PlanCode.DAILY);
    }

    @Test
    void dailyPlanIsADayPass() {
        Plan daily = catalog.plans().stream()
                .filter(p -> p.code() == PlanCode.DAILY).findFirst().orElseThrow();
        assertThat(daily.period()).isEqualTo(BillingPeriod.DAILY);
        assertThat(daily.providerMode()).isEqualTo(ProviderMode.HOSTED);
    }

    @Test
    void containsRecognisesKnownPlans() {
        assertThat(catalog.contains(PlanCode.SOLO)).isTrue();
        assertThat(catalog.contains(PlanCode.PRO)).isTrue();
    }
}
