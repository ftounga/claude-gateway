package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.billing.provider.BillingProviderUnavailableException;
import fr.claudegateway.billing.provider.CheckoutCommand;
import fr.claudegateway.billing.provider.StripeBillingProvider;
import fr.claudegateway.billing.provider.WebhookVerificationException;

/**
 * Tests unitaires du fournisseur Stripe (SF-09-02) sans réseau : configuration dormante et rejet de
 * signature invalide (la vérification de signature s'exécute localement, sans appel Stripe).
 */
class StripeBillingProviderTest {

    private StripeBillingProvider provider(String secretKey, String webhookSecret) {
        return new StripeBillingProvider(new BillingProperties(14, new BillingProperties.Stripe(
                secretKey, webhookSecret, Map.of("PRO", "price_pro"), null, null)));
    }

    @Test
    void notConfiguredWhenSecretKeyBlank() {
        assertThat(provider("", "whsec").isConfigured()).isFalse();
    }

    @Test
    void configuredWhenSecretKeyPresent() {
        assertThat(provider("sk_test", "whsec").isConfigured()).isTrue();
    }

    @Test
    void checkoutFailsWhenNotConfigured() {
        CheckoutCommand cmd = new CheckoutCommand(
                UUID.randomUUID(), "a@b.co", null,
                new Plan(PlanCode.PRO, "Pro", ProviderMode.HOSTED, BillingPeriod.MONTHLY), "price_pro");
        assertThatThrownBy(() -> provider("", "whsec").createCheckoutSession(cmd))
                .isInstanceOf(BillingProviderUnavailableException.class);
    }

    @Test
    void webhookFailsWhenSecretMissing() {
        assertThatThrownBy(() -> provider("sk_test", "").parseWebhookEvent("{}", "sig"))
                .isInstanceOf(BillingProviderUnavailableException.class);
    }

    @Test
    void webhookRejectsMissingSignatureHeader() {
        assertThatThrownBy(() -> provider("sk_test", "whsec").parseWebhookEvent("{}", null))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void webhookRejectsInvalidSignature() {
        assertThatThrownBy(() -> provider("sk_test", "whsec_test")
                .parseWebhookEvent("{\"id\":\"evt_1\"}", "t=123,v1=deadbeef"))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void statusMappingCoversKnownStripeStates() {
        assertThat(SubscriptionStatus.fromStripe("active")).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(SubscriptionStatus.fromStripe("trialing")).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(SubscriptionStatus.fromStripe("past_due")).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(SubscriptionStatus.fromStripe("canceled")).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(SubscriptionStatus.fromStripe("unpaid")).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(SubscriptionStatus.fromStripe("weird")).isEqualTo(SubscriptionStatus.INCOMPLETE);
        assertThat(SubscriptionStatus.fromStripe(null)).isEqualTo(SubscriptionStatus.INCOMPLETE);
    }
}
