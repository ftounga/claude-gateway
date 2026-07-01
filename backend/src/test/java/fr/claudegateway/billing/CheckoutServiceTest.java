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
import fr.claudegateway.billing.provider.CheckoutCommand;
import fr.claudegateway.billing.provider.CheckoutSession;

/** Tests unitaires de l'orchestration du checkout (SF-09-02). Provider et repo mockés. */
class CheckoutServiceTest {

    private SubscriptionService subscriptionService;
    private BillingProvider billingProvider;
    private CheckoutService service;

    @BeforeEach
    void setUp() {
        subscriptionService = mock(SubscriptionService.class);
        billingProvider = mock(BillingProvider.class);
        BillingProperties properties = new BillingProperties(14, new BillingProperties.Stripe(
                "sk_test", "whsec_test", Map.of("PRO", "price_pro"), null, null));
        service = new CheckoutService(new PlanCatalog(), subscriptionService, billingProvider, properties);
    }

    @Test
    void rejectsUnknownPlan() {
        assertThatThrownBy(() -> service.createCheckout(UUID.randomUUID(), "a@b.co", "PLATINUM"))
                .isInstanceOf(UnknownPlanException.class);
        verify(billingProvider, never()).createCheckoutSession(any());
    }

    @Test
    void rejectsBlankPlan() {
        assertThatThrownBy(() -> service.createCheckout(UUID.randomUUID(), "a@b.co", "  "))
                .isInstanceOf(UnknownPlanException.class);
    }

    @Test
    void delegatesToProviderWithResolvedPriceAndExistingCustomer() {
        UUID userId = UUID.randomUUID();
        Subscription existing = Subscription.builder()
                .userId(userId).status(SubscriptionStatus.TRIALING).stripeCustomerId("cus_123").build();
        when(subscriptionService.getOrCreateForUser(userId)).thenReturn(existing);
        when(billingProvider.createCheckoutSession(any()))
                .thenReturn(new CheckoutSession("https://checkout.stripe/x", "cs_1"));

        CheckoutSession result = service.createCheckout(userId, "a@b.co", "pro");

        ArgumentCaptor<CheckoutCommand> captor = ArgumentCaptor.forClass(CheckoutCommand.class);
        verify(billingProvider).createCheckoutSession(captor.capture());
        CheckoutCommand cmd = captor.getValue();
        assertThat(cmd.userId()).isEqualTo(userId);
        assertThat(cmd.priceId()).isEqualTo("price_pro");
        assertThat(cmd.existingCustomerId()).isEqualTo("cus_123");
        assertThat(cmd.plan().code()).isEqualTo(PlanCode.PRO);
        assertThat(result.url()).isEqualTo("https://checkout.stripe/x");
    }
}
