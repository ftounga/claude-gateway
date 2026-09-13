package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import fr.claudegateway.billing.VigieOptionService.VigieOptionView;
import fr.claudegateway.billing.provider.BillingProvider;
import fr.claudegateway.billing.provider.BillingProviderUnavailableException;
import fr.claudegateway.billing.provider.CheckoutSession;
import fr.claudegateway.billing.provider.VigieOptionCheckoutCommand;

/**
 * Tests unitaires de l'option Vigie (F-107 / SF-107-03) : description, souscription, résiliation. Miroir
 * de l'option Forge : aucun appel au fournisseur quand le refus se décide en local, et la résiliation ne
 * coupe rien sur-le-champ.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VigieOptionServiceTest {

    @Mock private SubscriptionService subscriptionService;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private BillingProvider billingProvider;
    @Mock private fr.claudegateway.access.AccessGrantService accessGrantService;
    @Mock private AdministratorEntitlement administratorEntitlement;

    private VigieOptionService service;

    private final UUID userId = UUID.randomUUID();

    private static BillingProperties properties(String vigiePriceId, String vigieDisplayPrice) {
        return new BillingProperties(5, new BillingProperties.Stripe(
                "sk_test", "whsec_test", Map.of(), Map.of(), null, null, Map.of(),
                "price_forge", "40", Map.of(), Map.of(), Map.of(), null, null,
                vigiePriceId, vigieDisplayPrice));
    }

    private void withProperties(BillingProperties props) {
        service = new VigieOptionService(subscriptionService, subscriptionRepository,
                new SpaceEntitlementService(subscriptionService, accessGrantService, administratorEntitlement),
                billingProvider, props);
    }

    @BeforeEach
    void setUp() {
        withProperties(properties("price_vigie", null));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Subscription given(PlanCode plan, SubscriptionStatus status, SubscriptionStatus option) {
        Subscription subscription = Subscription.builder()
                .userId(userId).planCode(plan).status(status)
                .teamsOptionStatus(option)
                .stripeCustomerId("cus_1")
                .vigieOptionStripeSubscriptionId(option == null ? null : "sub_vigie")
                .build();
        when(subscriptionService.getOrCreateForUser(userId)).thenReturn(subscription);
        return subscription;
    }

    // ------------------------------------------------------------------- description

    @Test
    @DisplayName("Solo : 69 € par défaut, pas encore de droit, souscriptible avec un price")
    void soloViewDefaultsTo69() {
        given(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null);

        VigieOptionView view = service.describe(userId);

        assertThat(view.priceEur()).isEqualTo("69");
        assertThat(view.entitled()).isFalse();
        assertThat(view.includedInPlan()).isFalse();
        assertThat(view.available()).isTrue();
        assertThat(view.goldCarrier()).isFalse();
    }

    @Test
    @DisplayName("price vide : l'option est indisponible, sans erreur")
    void emptyPriceMakesTheOptionUnavailable() {
        withProperties(properties("", "69"));
        given(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null);

        assertThat(service.describe(userId).available()).isFalse();
    }

    @Test
    @DisplayName("Gold Vigie et Gold complet : incluse")
    void spaceGoldsIncludeTheVigie() {
        given(PlanCode.GOLD_VIGIE, SubscriptionStatus.ACTIVE, null);
        assertThat(service.describe(userId).includedInPlan()).isTrue();
        assertThat(service.describe(userId).entitled()).isTrue();

        given(PlanCode.GOLD_COMPLETE, SubscriptionStatus.ACTIVE, null);
        assertThat(service.describe(userId).includedInPlan()).isTrue();
    }

    @Test
    @DisplayName("Gold Forge porte l'option : l'écran peut dire que Gold complet est moins cher")
    void goldForgeIsFlaggedAsGoldCarrier() {
        given(PlanCode.GOLD, SubscriptionStatus.ACTIVE, null);

        VigieOptionView view = service.describe(userId);
        assertThat(view.goldCarrier()).isTrue();
        assertThat(view.includedInPlan()).isFalse();
    }

    @Test
    @DisplayName("administrateur : incluse (administrateur)")
    void administratorIsIncluded() {
        when(administratorEntitlement.isAdministrator(userId)).thenReturn(true);
        given(null, SubscriptionStatus.TRIALING, null);

        VigieOptionView view = service.describe(userId);
        assertThat(view.includedForAdministrator()).isTrue();
        assertThat(view.entitled()).isTrue();
        assertThat(view.includedInPlan()).isFalse();
    }

    // ------------------------------------------------------------------- souscription

    @Test
    @DisplayName("BYOK actif : la session porte le price de l'option Vigie et le client connu")
    void byokActiveGetsACheckout() {
        given(PlanCode.BYOK, SubscriptionStatus.ACTIVE, null);
        when(billingProvider.createVigieOptionCheckoutSession(any()))
                .thenReturn(new CheckoutSession("https://pay/v", "cs_v"));

        CheckoutSession session = service.startCheckout(userId, "a@b.co");

        assertThat(session.url()).isEqualTo("https://pay/v");
        ArgumentCaptor<VigieOptionCheckoutCommand> captor = ArgumentCaptor.forClass(VigieOptionCheckoutCommand.class);
        verify(billingProvider).createVigieOptionCheckoutSession(captor.capture());
        assertThat(captor.getValue().priceId()).isEqualTo("price_vigie");
        assertThat(captor.getValue().existingCustomerId()).isEqualTo("cus_1");
        assertThat(captor.getValue().userId()).isEqualTo(userId);
    }

    @Test
    void goldForgeCanBuyTheOption() {
        given(PlanCode.GOLD, SubscriptionStatus.ACTIVE, null);
        when(billingProvider.createVigieOptionCheckoutSession(any()))
                .thenReturn(new CheckoutSession("https://pay/v", "cs_v"));

        assertThat(service.startCheckout(userId, "a@b.co")).isNotNull();
    }

    @Test
    @DisplayName("Gold Vigie : refus 409, on ne vend pas ce qui est inclus")
    void includedIsRefusedWithoutCallingTheProvider() {
        given(PlanCode.GOLD_VIGIE, SubscriptionStatus.ACTIVE, null);

        assertThatThrownBy(() -> service.startCheckout(userId, "a@b.co"))
                .isInstanceOf(VigieOptionIncludedInPlanException.class);
        verify(billingProvider, never()).createVigieOptionCheckoutSession(any());
    }

    @Test
    void trialAndCanceledPlansAreRefused() {
        given(null, SubscriptionStatus.TRIALING, null);
        assertThatThrownBy(() -> service.startCheckout(userId, "a@b.co"))
                .isInstanceOf(NoActiveSubscriptionException.class);

        given(PlanCode.PRO, SubscriptionStatus.CANCELED, null);
        assertThatThrownBy(() -> service.startCheckout(userId, "a@b.co"))
                .isInstanceOf(NoActiveSubscriptionException.class);
        verify(billingProvider, never()).createVigieOptionCheckoutSession(any());
    }

    @Test
    void alreadyActiveOptionIsRefused() {
        given(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE);

        assertThatThrownBy(() -> service.startCheckout(userId, "a@b.co"))
                .isInstanceOf(VigieOptionAlreadyActiveException.class);
    }

    @Test
    void missingPriceMakesTheOptionDormant() {
        withProperties(properties("", "69"));
        given(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null);

        assertThatThrownBy(() -> service.startCheckout(userId, "a@b.co"))
                .isInstanceOf(BillingProviderUnavailableException.class);
        verify(billingProvider, never()).createVigieOptionCheckoutSession(any());
    }

    // -------------------------------------------------------------------- résiliation

    @Test
    @DisplayName("Résilier programme le terme et garde le droit : le mois est payé")
    void cancelSchedulesTheEnd() {
        given(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE);
        OffsetDateTime term = OffsetDateTime.now().plusDays(12);
        when(billingProvider.scheduleSubscriptionCancellation("sub_vigie")).thenReturn(term);

        VigieOptionView view = service.cancel(userId);

        assertThat(view.cancelAt()).isEqualTo(term);
        assertThat(view.optionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(view.entitled()).isTrue();
    }

    @Test
    void cancelWithoutOptionIsRefused() {
        given(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null);

        assertThatThrownBy(() -> service.cancel(userId)).isInstanceOf(VigieOptionNotActiveException.class);
        verify(billingProvider, never()).scheduleSubscriptionCancellation(any());
    }
}
