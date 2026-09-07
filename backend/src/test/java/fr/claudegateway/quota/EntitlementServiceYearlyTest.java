package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fr.claudegateway.billing.BillingPeriod;
import fr.claudegateway.billing.PlanCatalog;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionStatus;

/**
 * F-43 / SF-43-02 — <b>l'engagement est annuel, l'allocation reste mensuelle</b>.
 *
 * <p>C'est la règle qui structure toute la feature, et la seule que le code aurait pu trahir en
 * silence : un quota annuel changerait la nature du produit et exposerait à une consommation
 * intégrale dès le premier mois — un abonné Pro engagé à l'année pourrait brûler 60 M de jetons en
 * janvier, puis coûter zéro revenu marginal pendant onze mois.</p>
 *
 * <p>Ces tests figent le fait que {@code resolveMonthlyTokenQuota} ne lit <b>pas</b>
 * {@code billing_period}. Ils échoueront le jour où quelqu'un tentera de faire dépendre l'allocation
 * de la périodicité, ce qui est exactement leur raison d'être.</p>
 */
class EntitlementServiceYearlyTest {

    private static final long SOLO_MONTHLY_TOKENS = 1_000_000L;
    private static final long PRO_MONTHLY_TOKENS = 5_000_000L;

    private EntitlementService service;

    @BeforeEach
    void setUp() {
        QuotaProperties properties = new QuotaProperties(
                200_000L,
                Map.of("SOLO", SOLO_MONTHLY_TOKENS, "PRO", PRO_MONTHLY_TOKENS, "DAILY", 500_000L,
                        "GOLD", 12_000_000L, "BYOK", 0L),
                null);
        service = new EntitlementService(properties, new PlanCatalog());
    }

    private static Subscription active(PlanCode plan, BillingPeriod period) {
        return Subscription.builder()
                .userId(UUID.randomUUID())
                .status(SubscriptionStatus.ACTIVE)
                .planCode(plan)
                .billingPeriod(period)
                .build();
    }

    @Test
    void aYearlySubscriberGetsTheSameMonthlyAllocationAsAMonthlyOne() {
        long monthly = service.resolveMonthlyTokenQuota(active(PlanCode.SOLO, BillingPeriod.MONTHLY));
        long yearly = service.resolveMonthlyTokenQuota(active(PlanCode.SOLO, BillingPeriod.YEARLY));

        assertThat(yearly).isEqualTo(monthly).isEqualTo(SOLO_MONTHLY_TOKENS);
    }

    @Test
    void aYearlySubscriberNeverGetsTwelveMonthsOfTokensAtOnce() {
        long yearly = service.resolveMonthlyTokenQuota(active(PlanCode.PRO, BillingPeriod.YEARLY));

        assertThat(yearly).isEqualTo(PRO_MONTHLY_TOKENS);
        assertThat(yearly).isNotEqualTo(PRO_MONTHLY_TOKENS * 12);
    }

    @Test
    void theCommitmentPeriodChangesNothingForAnyPlan() {
        for (PlanCode plan : PlanCode.values()) {
            assertThat(service.resolveMonthlyTokenQuota(active(plan, BillingPeriod.YEARLY)))
                    .as("allocation de %s à l'année", plan)
                    .isEqualTo(service.resolveMonthlyTokenQuota(active(plan, BillingPeriod.MONTHLY)));
        }
    }

    @Test
    void aSubscriptionWithoutAnyRecordedCommitmentIsAllocatedNormally() {
        // Les abonnements antérieurs à F-43 portent billing_period = null. Ils ne doivent pas être
        // traités différemment : la colonne n'entre pas dans le calcul, absente ou pas.
        assertThat(service.resolveMonthlyTokenQuota(active(PlanCode.SOLO, null)))
                .isEqualTo(SOLO_MONTHLY_TOKENS);
    }

    @Test
    void aYearlyCommitmentDoesNotRescueAnExpiredSubscription() {
        // Le fail-closed reste la règle : s'engager à l'année n'ouvre aucun droit une fois
        // l'abonnement résilié.
        Subscription canceled = Subscription.builder()
                .userId(UUID.randomUUID())
                .status(SubscriptionStatus.CANCELED)
                .planCode(PlanCode.PRO)
                .billingPeriod(BillingPeriod.YEARLY)
                .build();

        assertThat(service.resolveMonthlyTokenQuota(canceled)).isZero();
    }
}
