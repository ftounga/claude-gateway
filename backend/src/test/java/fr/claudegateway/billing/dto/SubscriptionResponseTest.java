package fr.claudegateway.billing.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.billing.BillingPeriod;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionStatus;

/**
 * Tests unitaires de la projection d'abonnement (F-09, complétée par F-66).
 *
 * <p>Deux choses s'y jouent : ce que la réponse <b>porte</b> — dont la durée et l'allocation de
 * l'essai, pour que les écrans cessent de les réciter de mémoire — et ce qu'elle ne porte
 * <b>jamais</b> : les identifiants Stripe.</p>
 */
class SubscriptionResponseTest {

    private Subscription subscription(SubscriptionStatus status, PlanCode planCode) {
        return Subscription.builder()
                .userId(UUID.randomUUID())
                .status(status)
                .planCode(planCode)
                .trialEndsAt(OffsetDateTime.parse("2026-07-24T08:00:00Z"))
                .currentPeriodEnd(OffsetDateTime.parse("2026-08-01T00:00:00Z"))
                .billingPeriod(BillingPeriod.MONTHLY)
                .stripeCustomerId("cus_secret")
                .stripeSubscriptionId("sub_secret")
                .build();
    }

    @Test
    void carriesTheTrialOfferAsServedByThisServer() {
        SubscriptionResponse response = SubscriptionResponse.from(
                subscription(SubscriptionStatus.TRIALING, null), false, 14, 200_000L);

        assertThat(response.status()).isEqualTo("TRIALING");
        assertThat(response.planCode()).isNull();
        assertThat(response.trialDays()).isEqualTo(14);
        assertThat(response.trialTokens()).isEqualTo(200_000L);
    }

    @Test
    void describesTheTrialOfferEvenForAPayingCustomer() {
        // L'offre d'essai est une propriété de l'OFFRE, pas de l'abonnement de qui la consulte :
        // la grille tarifaire doit pouvoir décrire l'essai à un client qui n'y a plus droit.
        SubscriptionResponse response = SubscriptionResponse.from(
                subscription(SubscriptionStatus.ACTIVE, PlanCode.PRO), false, 14, 200_000L);

        assertThat(response.planCode()).isEqualTo("PRO");
        assertThat(response.trialDays()).isEqualTo(14);
        assertThat(response.trialTokens()).isEqualTo(200_000L);
    }

    @Test
    void followsTheConfiguredValuesRatherThanAnyLiteral() {
        SubscriptionResponse response = SubscriptionResponse.from(
                subscription(SubscriptionStatus.TRIALING, null), false, 7, 30_000L);

        assertThat(response.trialDays()).isEqualTo(7);
        assertThat(response.trialTokens()).isEqualTo(30_000L);
    }

    @Test
    void neverCarriesStripeIdentifiers() {
        SubscriptionResponse response = SubscriptionResponse.from(
                subscription(SubscriptionStatus.ACTIVE, PlanCode.SOLO), true, 14, 200_000L);

        assertThat(response.toString()).doesNotContain("cus_secret").doesNotContain("sub_secret");
        assertThat(response.customerKeyBilled()).isTrue();
        assertThat(response.billingPeriod()).isEqualTo("MONTHLY");
    }
}
