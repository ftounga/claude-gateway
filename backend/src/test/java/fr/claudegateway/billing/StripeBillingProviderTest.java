package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.billing.provider.AtelierOptionCheckoutCommand;
import fr.claudegateway.billing.provider.BillingProviderUnavailableException;
import fr.claudegateway.billing.provider.CheckoutCommand;
import fr.claudegateway.billing.provider.StripeBillingProvider;
import fr.claudegateway.billing.provider.TopUpCheckoutCommand;
import fr.claudegateway.billing.provider.WebhookVerificationException;

/**
 * Tests unitaires du fournisseur Stripe (SF-09-02) sans réseau : configuration dormante et rejet de
 * signature invalide (la vérification de signature s'exécute localement, sans appel Stripe).
 */
class StripeBillingProviderTest {

    private StripeBillingProvider provider(String secretKey, String webhookSecret) {
        return new StripeBillingProvider(new BillingProperties(14, new BillingProperties.Stripe(
                secretKey, webhookSecret, Map.of("PRO", "price_pro"),
                Map.of("STANDARD", "price_topup"), null, null, Map.of(),
                "price_atelier_option", "40")));
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
    void topUpCheckoutFailsWhenNotConfigured() {
        TopUpCheckoutCommand cmd = new TopUpCheckoutCommand(
                UUID.randomUUID(), "a@b.co", null, "STANDARD", "price_topup");
        assertThatThrownBy(() -> provider("", "whsec").createTopUpCheckoutSession(cmd))
                .isInstanceOf(BillingProviderUnavailableException.class);
    }

    @Test
    void topUpCheckoutFailsWhenPriceIdBlank() {
        TopUpCheckoutCommand cmd = new TopUpCheckoutCommand(
                UUID.randomUUID(), "a@b.co", null, "STANDARD", "");
        assertThatThrownBy(() -> provider("sk_test", "whsec").createTopUpCheckoutSession(cmd))
                .isInstanceOf(BillingProviderUnavailableException.class);
    }

    // ------------------------------------------------ option Atelier (F-40 / SF-40-02)

    @Test
    void atelierOptionCheckoutFailsWhenNotConfigured() {
        AtelierOptionCheckoutCommand cmd = new AtelierOptionCheckoutCommand(
                UUID.randomUUID(), "a@b.co", null, "price_atelier_option");
        assertThatThrownBy(() -> provider("", "whsec").createAtelierOptionCheckoutSession(cmd))
                .isInstanceOf(BillingProviderUnavailableException.class);
    }

    @Test
    void atelierOptionCheckoutFailsWhenPriceIdBlank() {
        AtelierOptionCheckoutCommand cmd = new AtelierOptionCheckoutCommand(
                UUID.randomUUID(), "a@b.co", null, "");
        assertThatThrownBy(() -> provider("sk_test", "whsec").createAtelierOptionCheckoutSession(cmd))
                .isInstanceOf(BillingProviderUnavailableException.class);
    }

    @Test
    void scheduledCancellationFailsWhenNotConfigured() {
        assertThatThrownBy(() -> provider("", "whsec").scheduleSubscriptionCancellation("sub_1"))
                .isInstanceOf(BillingProviderUnavailableException.class);
    }

    @Test
    void scheduledCancellationFailsWhenSubscriptionIdBlank() {
        assertThatThrownBy(() -> provider("sk_test", "whsec").scheduleSubscriptionCancellation(""))
                .isInstanceOf(BillingProviderUnavailableException.class);
    }

    @Test
    void atelierOptionPriceIsReadFromConfiguration() {
        // Le price ID de l'option ne se déduit d'aucun plan : il est nommément configuré.
        assertThat(provider("sk_test", "whsec")).isNotNull();
        assertThat(new BillingProperties(14, new BillingProperties.Stripe(
                "sk", "wh", Map.of(), Map.of(), null, null, Map.of(), "price_opt", "40"))
                .stripe().isAtelierOptionConfigured()).isTrue();
        assertThat(new BillingProperties(14, new BillingProperties.Stripe(
                "sk", "wh", Map.of(), Map.of(), null, null, Map.of(), "", "40"))
                .stripe().isAtelierOptionConfigured()).isFalse();
        assertThat(new BillingProperties(14, new BillingProperties.Stripe(
                "", "wh", Map.of(), Map.of(), null, null, Map.of(), "price_opt", "40"))
                .stripe().isAtelierOptionConfigured()).isFalse();
        // Défaut de la feature : 40 €/mois, même si la configuration ne le dit pas.
        assertThat(new BillingProperties(14, new BillingProperties.Stripe(
                "sk", "wh", Map.of(), Map.of(), null, null, Map.of(), "price_opt", null))
                .stripe().atelierOptionDisplayPrice()).isEqualTo("40");
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
