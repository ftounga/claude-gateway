package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import fr.claudegateway.billing.provider.BillingProvider;
import fr.claudegateway.billing.provider.ChangePlanCommand;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests unitaires du provisionnement d'essai (SF-09-01). Repository mocké : on valide la logique
 * métier (idempotence, valeurs initiales du trial) sans base.
 */
class SubscriptionServiceTest {

    private SubscriptionRepository repository;
    private SubscriptionService service;

    @BeforeEach
    void setUp() {
        repository = org.mockito.Mockito.mock(SubscriptionRepository.class);
        service = new SubscriptionService(repository, new BillingProperties(14, null), new PlanCatalog(),
                org.mockito.Mockito.mock(fr.claudegateway.billing.provider.BillingProvider.class));
    }

    @Test
    void provisionsTrialWhenNoSubscriptionExists() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());
        when(repository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));

        Subscription result = service.getOrCreateForUser(userId);

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(repository).save(captor.capture());
        Subscription saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(saved.getPlanCode()).isNull();
        assertThat(saved.getTrialEndsAt()).isAfter(OffsetDateTime.now().plusDays(13));
        assertThat(saved.getTrialEndsAt()).isBefore(OffsetDateTime.now().plusDays(15));
        assertThat(result).isSameAs(saved);
    }

    @Test
    void returnsExistingSubscriptionWithoutCreating() {
        UUID userId = UUID.randomUUID();
        Subscription existing = Subscription.builder()
                .userId(userId).status(SubscriptionStatus.ACTIVE).planCode(PlanCode.PRO).build();
        when(repository.findByUserId(userId)).thenReturn(Optional.of(existing));

        Subscription result = service.getOrCreateForUser(userId);

        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void usesConfiguredTrialDuration() {
        UUID userId = UUID.randomUUID();
        SubscriptionService sevenDayService = new SubscriptionService(repository, new BillingProperties(7, null),
                new PlanCatalog(), org.mockito.Mockito.mock(fr.claudegateway.billing.provider.BillingProvider.class));
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());
        when(repository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));

        Subscription result = sevenDayService.getOrCreateForUser(userId);

        assertThat(result.getTrialEndsAt()).isAfter(OffsetDateTime.now().plusDays(6));
        assertThat(result.getTrialEndsAt()).isBefore(OffsetDateTime.now().plusDays(8));
        verify(repository, times(1)).save(any());
    }

    @Test
    void defaultsTrialDaysWhenPropertyInvalid() {
        assertThat(new BillingProperties(null, null).trialDays()).isEqualTo(5);
        assertThat(new BillingProperties(0, null).trialDays()).isEqualTo(5);
        assertThat(new BillingProperties(-3, null).trialDays()).isEqualTo(5);
    }

    // ---- Changement de plan (upgrade/downgrade, SF-21-05) ----

    private SubscriptionService serviceWithProvider(BillingProvider provider) {
        BillingProperties props = new BillingProperties(5, new BillingProperties.Stripe(
                "sk", "wh", Map.of("PRO", "price_pro"), Map.of(), null, null, Map.of()));
        return new SubscriptionService(repository, props, new PlanCatalog(), provider);
    }

    @Test
    void changePlanUpdatesStripeAndLocalPlanWhenActive() {
        UUID userId = UUID.randomUUID();
        BillingProvider provider = mock(BillingProvider.class);
        SubscriptionService svc = serviceWithProvider(provider);
        Subscription active = Subscription.builder()
                .userId(userId).status(SubscriptionStatus.ACTIVE).planCode(PlanCode.SOLO)
                .stripeSubscriptionId("sub_123").build();
        when(repository.findByUserId(userId)).thenReturn(Optional.of(active));
        when(repository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));

        Subscription result = svc.changePlan(userId, "PRO");

        ArgumentCaptor<ChangePlanCommand> captor = ArgumentCaptor.forClass(ChangePlanCommand.class);
        verify(provider).changeSubscriptionPlan(captor.capture());
        assertThat(captor.getValue().stripeSubscriptionId()).isEqualTo("sub_123");
        assertThat(captor.getValue().newPriceId()).isEqualTo("price_pro");
        assertThat(result.getPlanCode()).isEqualTo(PlanCode.PRO);
    }

    @Test
    void changePlanRejectsWhenNoActiveSubscription() {
        UUID userId = UUID.randomUUID();
        BillingProvider provider = mock(BillingProvider.class);
        SubscriptionService svc = serviceWithProvider(provider);
        // Encore en essai : aucun stripeSubscriptionId.
        Subscription trial = Subscription.builder()
                .userId(userId).status(SubscriptionStatus.TRIALING).build();
        when(repository.findByUserId(userId)).thenReturn(Optional.of(trial));

        assertThatThrownBy(() -> svc.changePlan(userId, "PRO"))
                .isInstanceOf(NoActiveSubscriptionException.class);
        verify(provider, never()).changeSubscriptionPlan(any());
    }

    @Test
    void changePlanRejectsUnknownPlan() {
        UUID userId = UUID.randomUUID();
        BillingProvider provider = mock(BillingProvider.class);
        SubscriptionService svc = serviceWithProvider(provider);

        assertThatThrownBy(() -> svc.changePlan(userId, "GHOST"))
                .isInstanceOf(UnknownPlanException.class);
        verify(provider, never()).changeSubscriptionPlan(any());
    }
}
