package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionStatus;

/**
 * Tests unitaires de la résolution d'entitlement (SF-10-01) : traduction de l'état d'abonnement
 * (F-09) en allocation mensuelle de tokens, fail-closed pour tout état n'ouvrant pas d'accès.
 */
class EntitlementServiceTest {

    private EntitlementService service;

    @BeforeEach
    void setUp() {
        QuotaProperties properties = new QuotaProperties(
                200_000L,
                Map.of("SOLO", 1_000_000L, "PRO", 5_000_000L, "DAILY", 500_000L));
        service = new EntitlementService(properties);
    }

    private Subscription subscription(SubscriptionStatus status, PlanCode plan, OffsetDateTime trialEndsAt) {
        return Subscription.builder()
                .userId(UUID.randomUUID())
                .status(status)
                .planCode(plan)
                .trialEndsAt(trialEndsAt)
                .build();
    }

    @Test
    void activePlanResolvesToPlanQuota() {
        assertThat(service.resolveMonthlyTokenQuota(
                subscription(SubscriptionStatus.ACTIVE, PlanCode.PRO, null)))
                .isEqualTo(5_000_000L);
    }

    @Test
    void pastDueKeepsPlanQuotaAsGrace() {
        assertThat(service.resolveMonthlyTokenQuota(
                subscription(SubscriptionStatus.PAST_DUE, PlanCode.SOLO, null)))
                .isEqualTo(1_000_000L);
    }

    @Test
    void activeTrialResolvesToTrialQuota() {
        assertThat(service.resolveMonthlyTokenQuota(
                subscription(SubscriptionStatus.TRIALING, null, OffsetDateTime.now().plusDays(5))))
                .isEqualTo(200_000L);
    }

    @Test
    void expiredTrialResolvesToZero() {
        assertThat(service.resolveMonthlyTokenQuota(
                subscription(SubscriptionStatus.TRIALING, null, OffsetDateTime.now().minusDays(1))))
                .isZero();
    }

    @Test
    void canceledResolvesToZero() {
        assertThat(service.resolveMonthlyTokenQuota(
                subscription(SubscriptionStatus.CANCELED, PlanCode.PRO, null)))
                .isZero();
    }

    @Test
    void incompleteResolvesToZero() {
        assertThat(service.resolveMonthlyTokenQuota(
                subscription(SubscriptionStatus.INCOMPLETE, null, null)))
                .isZero();
    }

    @Test
    void activeWithUnconfiguredPlanFailsClosed() {
        EntitlementService noPlans = new EntitlementService(new QuotaProperties(200_000L, Map.of()));
        assertThat(noPlans.resolveMonthlyTokenQuota(
                subscription(SubscriptionStatus.ACTIVE, PlanCode.PRO, null)))
                .isZero();
    }
}
