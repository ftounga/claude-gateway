package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.billing.AtelierOptionService.AtelierOptionView;
import fr.claudegateway.billing.provider.AtelierOptionCheckoutCommand;
import fr.claudegateway.billing.provider.BillingProvider;
import fr.claudegateway.billing.provider.BillingProviderUnavailableException;
import fr.claudegateway.billing.provider.CheckoutSession;

/**
 * Tests unitaires de la souscription et de la résiliation de l'option Atelier (F-40 / SF-40-02).
 *
 * <p>Deux propriétés sont figées ici plus que les autres : aucun appel au fournisseur n'est fait
 * quand le refus se décide en local (on ne crée pas une session de paiement pour la jeter), et la
 * résiliation ne coupe <b>rien</b> sur-le-champ — le mois est payé, il est dû jusqu'au terme.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierOptionServiceTest {

    @Mock private SubscriptionService subscriptionService;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private BillingProvider billingProvider;

    private AtelierOptionService service;

    private final UUID userId = UUID.randomUUID();

    private static BillingProperties properties(String optionPriceId, String displayPrice) {
        return new BillingProperties(5, new BillingProperties.Stripe(
                "sk_test", "whsec_test", Map.of(), Map.of(), null, null, Map.of(),
                optionPriceId, displayPrice, Map.of(), Map.of()));
    }

    private void withProperties(BillingProperties props) {
        service = new AtelierOptionService(subscriptionService, subscriptionRepository,
                new AtelierEntitlementService(subscriptionService), billingProvider, props);
    }

    @BeforeEach
    void setUp() {
        withProperties(properties("price_atelier_option", "40"));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Subscription given(PlanCode plan, SubscriptionStatus status, SubscriptionStatus option) {
        Subscription subscription = Subscription.builder()
                .userId(userId).planCode(plan).status(status)
                .atelierOptionStatus(option)
                .stripeCustomerId("cus_1")
                .atelierOptionStripeSubscriptionId(option == null ? null : "sub_option")
                .build();
        when(subscriptionService.getOrCreateForUser(userId)).thenReturn(subscription);
        return subscription;
    }

    // ------------------------------------------------------------------- souscription

    @Test
    @DisplayName("Solo actif : la session porte le price de configuration et le client déjà connu")
    void soloActiveGetsACheckout() {
        given(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null);
        when(billingProvider.createAtelierOptionCheckoutSession(any()))
                .thenReturn(new CheckoutSession("https://pay/x", "cs_1"));

        CheckoutSession session = service.startCheckout(userId, "alice@example.com");

        assertThat(session.url()).isEqualTo("https://pay/x");
        ArgumentCaptor<AtelierOptionCheckoutCommand> captor =
                ArgumentCaptor.forClass(AtelierOptionCheckoutCommand.class);
        verify(billingProvider).createAtelierOptionCheckoutSession(captor.capture());
        assertThat(captor.getValue().priceId()).isEqualTo("price_atelier_option");
        assertThat(captor.getValue().existingCustomerId()).isEqualTo("cus_1");
        assertThat(captor.getValue().userId()).isEqualTo(userId);
    }

    @Test
    void proActiveGetsACheckout() {
        given(PlanCode.PRO, SubscriptionStatus.ACTIVE, null);
        when(billingProvider.createAtelierOptionCheckoutSession(any()))
                .thenReturn(new CheckoutSession("https://pay/x", "cs_1"));

        assertThat(service.startCheckout(userId, "a@b.co")).isNotNull();
    }

    @Test
    @DisplayName("Gold : refus, on ne vend pas ce qui est déjà inclus")
    void goldIsRefusedWithoutCallingTheProvider() {
        given(PlanCode.GOLD, SubscriptionStatus.ACTIVE, null);

        assertThatThrownBy(() -> service.startCheckout(userId, "a@b.co"))
                .isInstanceOf(AtelierOptionIncludedInPlanException.class);
        verify(billingProvider, never()).createAtelierOptionCheckoutSession(any());
    }

    @Test
    void trialIsRefusedWithoutCallingTheProvider() {
        given(null, SubscriptionStatus.TRIALING, null);

        assertThatThrownBy(() -> service.startCheckout(userId, "a@b.co"))
                .isInstanceOf(NoActiveSubscriptionException.class);
        verify(billingProvider, never()).createAtelierOptionCheckoutSession(any());
    }

    @Test
    void canceledPlanIsRefused() {
        given(PlanCode.SOLO, SubscriptionStatus.CANCELED, null);

        assertThatThrownBy(() -> service.startCheckout(userId, "a@b.co"))
                .isInstanceOf(NoActiveSubscriptionException.class);
    }

    @Test
    @DisplayName("Pass journée : refus, il ne porte pas un abonnement mensuel")
    void dayPassIsRefused() {
        given(PlanCode.DAILY, SubscriptionStatus.ACTIVE, null);

        assertThatThrownBy(() -> service.startCheckout(userId, "a@b.co"))
                .isInstanceOf(NoActiveSubscriptionException.class);
    }

    @Test
    void alreadyActiveOptionIsRefused() {
        given(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE);

        assertThatThrownBy(() -> service.startCheckout(userId, "a@b.co"))
                .isInstanceOf(AtelierOptionAlreadyActiveException.class);
        verify(billingProvider, never()).createAtelierOptionCheckoutSession(any());
    }

    @Test
    void pastDueOptionIsRefusedToo() {
        given(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE);

        assertThatThrownBy(() -> service.startCheckout(userId, "a@b.co"))
                .isInstanceOf(AtelierOptionAlreadyActiveException.class);
    }

    @Test
    @DisplayName("Une option déjà résiliée se re-souscrit")
    void canceledOptionCanBeSubscribedAgain() {
        given(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELED);
        when(billingProvider.createAtelierOptionCheckoutSession(any()))
                .thenReturn(new CheckoutSession("https://pay/x", "cs_1"));

        assertThat(service.startCheckout(userId, "a@b.co")).isNotNull();
    }

    @Test
    void missingPriceMakesTheOptionDormant() {
        withProperties(properties("", "40"));
        given(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null);

        assertThatThrownBy(() -> service.startCheckout(userId, "a@b.co"))
                .isInstanceOf(BillingProviderUnavailableException.class);
        verify(billingProvider, never()).createAtelierOptionCheckoutSession(any());
    }

    // -------------------------------------------------------------------- résiliation

    @Test
    @DisplayName("Résilier programme le terme et ne coupe rien : le mois est payé")
    void cancelSchedulesTheEndAndKeepsTheRight() {
        Subscription subscription = given(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE);
        OffsetDateTime term = OffsetDateTime.now().plusDays(20);
        when(billingProvider.scheduleSubscriptionCancellation("sub_option")).thenReturn(term);

        AtelierOptionView view = service.cancel(userId);

        verify(billingProvider).scheduleSubscriptionCancellation("sub_option");
        assertThat(subscription.getAtelierOptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.getAtelierOptionCancelAt()).isEqualTo(term);
        assertThat(view.entitled()).as("le droit reste ouvert jusqu'au terme").isTrue();
        assertThat(view.cancelAt()).isEqualTo(term);
    }

    @Test
    void cancelWithoutOptionIsRefusedWithoutCallingTheProvider() {
        given(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null);

        assertThatThrownBy(() -> service.cancel(userId))
                .isInstanceOf(AtelierOptionNotActiveException.class);
        verify(billingProvider, never()).scheduleSubscriptionCancellation(anyString());
    }

    @Test
    void cancelOnAnAlreadyCanceledOptionIsRefused() {
        given(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELED);

        assertThatThrownBy(() -> service.cancel(userId))
                .isInstanceOf(AtelierOptionNotActiveException.class);
    }

    // -------------------------------------------------------------------- description

    @Test
    void describeReadsThePriceFromConfiguration() {
        withProperties(properties("price_atelier_option", "59"));
        given(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null);

        AtelierOptionView view = service.describe(userId);

        assertThat(view.priceEur()).isEqualTo("59");
        assertThat(view.entitled()).isFalse();
        assertThat(view.includedInPlan()).isFalse();
        assertThat(view.optionStatus()).isNull();
        assertThat(view.available()).isTrue();
    }

    @Test
    void describeSaysIncludedForGold() {
        given(PlanCode.GOLD, SubscriptionStatus.ACTIVE, null);

        AtelierOptionView view = service.describe(userId);

        assertThat(view.includedInPlan()).isTrue();
        assertThat(view.entitled()).isTrue();
    }

    @Test
    void describeSaysUnavailableWhenNoPriceIsConfigured() {
        withProperties(properties(null, "40"));
        given(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null);

        assertThat(service.describe(userId).available()).isFalse();
    }

    @Test
    void describeFallsBackToTheDefaultPriceWhenConfigurationIsEmpty() {
        withProperties(properties("price_atelier_option", ""));
        given(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null);

        assertThat(service.describe(userId).priceEur()).isEqualTo("40");
    }
}
