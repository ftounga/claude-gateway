package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fr.claudegateway.billing.provider.BillingProvider;
import fr.claudegateway.billing.provider.CheckoutSession;
import fr.claudegateway.billing.provider.TopUpCheckoutCommand;

/** Tests unitaires de l'orchestration du rachat de tokens (SF-21-02). Provider et service mockés. */
class TopUpServiceTest {

    private SubscriptionService subscriptionService;
    private BillingProvider billingProvider;
    private TopUpService service;

    @BeforeEach
    void setUp() {
        subscriptionService = mock(SubscriptionService.class);
        billingProvider = mock(BillingProvider.class);
        BillingProperties properties = new BillingProperties(14, new BillingProperties.Stripe(
                "sk_test", "whsec_test", Map.of(), Map.of("STANDARD", "price_topup"), null, null));
        service = new TopUpService(new TopUpCatalog(), subscriptionService, billingProvider, properties);
    }

    @Test
    void rejectsUnknownPack() {
        assertThatThrownBy(() -> service.createTopUpCheckout(UUID.randomUUID(), "a@b.co", "GHOST"))
                .isInstanceOf(UnknownPlanException.class);
        verify(billingProvider, never()).createTopUpCheckoutSession(any());
    }

    @Test
    void rejectsBlankPack() {
        assertThatThrownBy(() -> service.createTopUpCheckout(UUID.randomUUID(), "a@b.co", "  "))
                .isInstanceOf(UnknownPlanException.class);
    }

    @Test
    void delegatesToProviderWithResolvedPriceAndExistingCustomer() {
        UUID userId = UUID.randomUUID();
        Subscription existing = Subscription.builder()
                .userId(userId).status(SubscriptionStatus.TRIALING).stripeCustomerId("cus_123").build();
        when(subscriptionService.getOrCreateForUser(userId)).thenReturn(existing);
        when(billingProvider.createTopUpCheckoutSession(any()))
                .thenReturn(new CheckoutSession("https://checkout.stripe/x", "cs_1"));

        CheckoutSession result = service.createTopUpCheckout(userId, "a@b.co", "standard");

        ArgumentCaptor<TopUpCheckoutCommand> captor = ArgumentCaptor.forClass(TopUpCheckoutCommand.class);
        verify(billingProvider).createTopUpCheckoutSession(captor.capture());
        TopUpCheckoutCommand cmd = captor.getValue();
        assertThat(cmd.userId()).isEqualTo(userId);
        assertThat(cmd.packCode()).isEqualTo("STANDARD");
        assertThat(cmd.priceId()).isEqualTo("price_topup");
        assertThat(cmd.existingCustomerId()).isEqualTo("cus_123");
        assertThat(result.url()).isEqualTo("https://checkout.stripe/x");
    }
}
