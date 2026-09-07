package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests unitaires du catalogue de plans (SF-09-01). */
class PlanCatalogTest {

    private final PlanCatalog catalog = new PlanCatalog();

    @Test
    void exposesSoloProGoldAndByokPlans() {
        assertThat(catalog.plans())
                .extracting(Plan::code)
                .containsExactlyInAnyOrder(PlanCode.SOLO, PlanCode.PRO, PlanCode.GOLD, PlanCode.BYOK);
    }

    @Test
    void dailyIsNoLongerACatalogPlan() {
        // SF-09-04 : le pass journée n'a jamais eu de price ID Stripe, donc n'a jamais été vendable.
        // Son retrait devient une décision explicite, au lieu d'un effet de bord de configuration.
        assertThat(catalog.plans()).extracting(Plan::code).doesNotContain(PlanCode.DAILY);
        assertThat(catalog.contains(PlanCode.DAILY)).isFalse();
    }

    @Test
    void theDailyCodeItselfSurvivesForExistingSubscriptions() {
        // SF-09-04 / D1 : subscriptions.plan_code est un varchar sans contrainte d'énumération.
        // Retirer la constante ferait échouer la LECTURE d'un abonnement qui la porte — un incident,
        // alors que l'objectif est seulement de ne plus la VENDRE.
        assertThat(PlanCode.valueOf("DAILY")).isEqualTo(PlanCode.DAILY);
    }

    @Test
    void byokPlanIsTheOnlyCustomerKeyPlan() {
        // F-41 : le seul plan du catalogue dont les appels sont servis par la clé du client. Si un
        // autre plan basculait en ProviderMode.BYOK, il hériterait silencieusement de la dérogation
        // de quota — ce test l'interdit.
        assertThat(catalog.plans())
                .filteredOn(p -> p.providerMode() == ProviderMode.BYOK)
                .extracting(Plan::code)
                .containsExactly(PlanCode.BYOK);
    }

    @Test
    void byokPlanIsMonthly() {
        Plan byok = catalog.plans().stream()
                .filter(p -> p.code() == PlanCode.BYOK).findFirst().orElseThrow();
        assertThat(byok.period()).isEqualTo(BillingPeriod.MONTHLY);
    }

    @Test
    void goldPlanIsMonthlyHosted() {
        Plan gold = catalog.plans().stream()
                .filter(p -> p.code() == PlanCode.GOLD).findFirst().orElseThrow();
        assertThat(gold.period()).isEqualTo(BillingPeriod.MONTHLY);
        assertThat(gold.providerMode()).isEqualTo(ProviderMode.HOSTED);
    }

    @Test
    void noCatalogPlanIsBilledByTheDay() {
        // Remplace dailyPlanIsADayPass, devenu sans objet avec le retrait du pass journée
        // (SF-09-04). Ce qui reste à figer est l'inverse : aucun plan du catalogue n'est facturé à
        // la journée. La périodicité DAILY appartient désormais au seul pack de recharge.
        assertThat(catalog.plans()).extracting(Plan::period).doesNotContain(BillingPeriod.DAILY);
    }

    @Test
    void containsRecognisesKnownPlans() {
        assertThat(catalog.contains(PlanCode.SOLO)).isTrue();
        assertThat(catalog.contains(PlanCode.PRO)).isTrue();
    }
}
