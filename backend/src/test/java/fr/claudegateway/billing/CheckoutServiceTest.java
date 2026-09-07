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
        // SOLO est proposé au mois ET à l'année ; PRO au mois seulement. DAILY a un price unique.
        BillingProperties properties = new BillingProperties(14, new BillingProperties.Stripe(
                "sk_test", "whsec_test",
                Map.of("PRO", "price_pro", "SOLO", "price_solo", "DAILY", "price_daily"),
                Map.of(), null, null, Map.of(),
                null, null,
                Map.of("SOLO", "price_solo_yearly"), Map.of("SOLO", "240")));
        service = new CheckoutService(new PlanCatalog(), subscriptionService, billingProvider,
                new BillingPeriodSelection(properties));
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
        // Non-régression F-43 : sans périodicité demandée, la commande reste mensuelle.
        assertThat(cmd.period()).isEqualTo(BillingPeriod.MONTHLY);
    }

    // ------------------------------------------------ F-43 / SF-43-02 — engagement annuel

    private void withExistingSubscription(UUID userId) {
        Subscription existing = Subscription.builder()
                .userId(userId).status(SubscriptionStatus.TRIALING).stripeCustomerId("cus_123").build();
        when(subscriptionService.getOrCreateForUser(userId)).thenReturn(existing);
        when(billingProvider.createCheckoutSession(any()))
                .thenReturn(new CheckoutSession("https://checkout.stripe/x", "cs_1"));
    }

    private CheckoutCommand captureCommand() {
        ArgumentCaptor<CheckoutCommand> captor = ArgumentCaptor.forClass(CheckoutCommand.class);
        verify(billingProvider).createCheckoutSession(captor.capture());
        return captor.getValue();
    }

    @Test
    void usesTheYearlyPriceWhenTheYearlyPeriodIsRequested() {
        UUID userId = UUID.randomUUID();
        withExistingSubscription(userId);

        service.createCheckout(userId, "a@b.co", "SOLO", "YEARLY");

        CheckoutCommand cmd = captureCommand();
        assertThat(cmd.priceId()).isEqualTo("price_solo_yearly");
        assertThat(cmd.period()).isEqualTo(BillingPeriod.YEARLY);
    }

    @Test
    void keepsTheMonthlyPriceWhenNoPeriodIsRequested() {
        UUID userId = UUID.randomUUID();
        withExistingSubscription(userId);

        service.createCheckout(userId, "a@b.co", "SOLO", null);

        CheckoutCommand cmd = captureCommand();
        assertThat(cmd.priceId()).isEqualTo("price_solo");
        assertThat(cmd.period()).isEqualTo(BillingPeriod.MONTHLY);
    }

    @Test
    void acceptsAnExplicitMonthlyPeriod() {
        UUID userId = UUID.randomUUID();
        withExistingSubscription(userId);

        service.createCheckout(userId, "a@b.co", "solo", " monthly ");

        assertThat(captureCommand().period()).isEqualTo(BillingPeriod.MONTHLY);
    }

    @Test
    void refusesTheYearlyPeriodOnAPlanWithoutAYearlyOffer() {
        // Jamais de repli silencieux sur le price mensuel : le client cliquerait « à l'année » et
        // serait débité au mois, sans qu'aucun message ne le dise.
        assertThatThrownBy(() -> service.createCheckout(UUID.randomUUID(), "a@b.co", "PRO", "YEARLY"))
                .isInstanceOf(YearlyBillingUnavailableException.class);
        verify(billingProvider, never()).createCheckoutSession(any());
    }

    @Test
    void refusesAnUnknownPeriod() {
        assertThatThrownBy(() -> service.createCheckout(UUID.randomUUID(), "a@b.co", "SOLO", "WEEKLY"))
                .isInstanceOf(UnknownBillingPeriodException.class);
        verify(billingProvider, never()).createCheckoutSession(any());
    }

    @Test
    void refusesDailyAsAPurchasedPeriod() {
        // DAILY est une valeur légale de l'énumération, mais pas un choix d'achat : l'accepter
        // laisserait croire qu'on peut acheter un plan mensuel à la journée.
        assertThatThrownBy(() -> service.createCheckout(UUID.randomUUID(), "a@b.co", "SOLO", "DAILY"))
                .isInstanceOf(UnknownBillingPeriodException.class);
        verify(billingProvider, never()).createCheckoutSession(any());
    }

    @Test
    void dailyPassKeepsItsOwnPeriodWithoutTheClientAskingForIt() {
        UUID userId = UUID.randomUUID();
        withExistingSubscription(userId);

        service.createCheckout(userId, "a@b.co", "DAILY", null);

        CheckoutCommand cmd = captureCommand();
        assertThat(cmd.priceId()).isEqualTo("price_daily");
        assertThat(cmd.period()).isEqualTo(BillingPeriod.DAILY);
    }

    @Test
    void dailyPassCannotBeBoughtForAYear() {
        assertThatThrownBy(() -> service.createCheckout(UUID.randomUUID(), "a@b.co", "DAILY", "YEARLY"))
                .isInstanceOf(YearlyBillingUnavailableException.class);
        verify(billingProvider, never()).createCheckoutSession(any());
    }
}
