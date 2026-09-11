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
        BillingProperties defaults = new BillingProperties(14, null);
        service = new SubscriptionService(repository, defaults, new PlanCatalog(),
                org.mockito.Mockito.mock(fr.claudegateway.billing.provider.BillingProvider.class),
                new BillingPeriodSelection(defaults));
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
        BillingProperties sevenDays = new BillingProperties(7, null);
        SubscriptionService sevenDayService = new SubscriptionService(repository, sevenDays,
                new PlanCatalog(), org.mockito.Mockito.mock(fr.claudegateway.billing.provider.BillingProvider.class),
                new BillingPeriodSelection(sevenDays));
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());
        when(repository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));

        Subscription result = sevenDayService.getOrCreateForUser(userId);

        assertThat(result.getTrialEndsAt()).isAfter(OffsetDateTime.now().plusDays(6));
        assertThat(result.getTrialEndsAt()).isBefore(OffsetDateTime.now().plusDays(8));
        verify(repository, times(1)).save(any());
    }

    /**
     * Le défaut de code sert la durée <b>annoncée</b> (F-66). Une configuration absente ou invalide
     * ne doit pas servir un essai plus court que la promesse publique : c'est exactement l'écart que
     * F-66 referme.
     */
    @Test
    void defaultsTrialDaysToTheAdvertisedFourteenWhenPropertyInvalid() {
        assertThat(new BillingProperties(null, null).trialDays()).isEqualTo(14);
        assertThat(new BillingProperties(0, null).trialDays()).isEqualTo(14);
        assertThat(new BillingProperties(-3, null).trialDays()).isEqualTo(14);
    }

    @Test
    void provisionsFourteenDayTrialWithTheDefaultConfiguration() {
        UUID userId = UUID.randomUUID();
        BillingProperties defaults = new BillingProperties(null, null);
        SubscriptionService defaultService = new SubscriptionService(repository, defaults,
                new PlanCatalog(), mock(BillingProvider.class),
                new BillingPeriodSelection(defaults));
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());
        when(repository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));

        Subscription result = defaultService.getOrCreateForUser(userId);

        assertThat(result.getTrialEndsAt()).isAfter(OffsetDateTime.now().plusDays(13));
        assertThat(result.getTrialEndsAt()).isBefore(OffsetDateTime.now().plusDays(15));
    }

    // ---- Changement de plan (upgrade/downgrade, SF-21-05) ----

    private SubscriptionService serviceWithProvider(BillingProvider provider) {
        BillingProperties props = new BillingProperties(5, new BillingProperties.Stripe(
                "sk", "wh", Map.of("PRO", "price_pro", "SOLO", "price_solo"), Map.of(), null, null,
                Map.of(), null, null,
                Map.of("SOLO", "price_solo_yearly"), Map.of("SOLO", "240"), Map.of()));
        return new SubscriptionService(repository, props, new PlanCatalog(), provider,
                new BillingPeriodSelection(props));
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
        // Non-régression F-43 : un changement de plan sans périodicité reste mensuel.
        assertThat(result.getBillingPeriod()).isEqualTo(BillingPeriod.MONTHLY);
    }

    // ---- Changement de PÉRIODICITÉ (F-43 / SF-43-02) ----

    @Test
    void changePlanToTheYearlyPeriodSendsTheYearlyPriceAndRecordsTheCommitment() {
        UUID userId = UUID.randomUUID();
        BillingProvider provider = mock(BillingProvider.class);
        SubscriptionService svc = serviceWithProvider(provider);
        Subscription active = Subscription.builder()
                .userId(userId).status(SubscriptionStatus.ACTIVE).planCode(PlanCode.SOLO)
                .billingPeriod(BillingPeriod.MONTHLY)
                .stripeSubscriptionId("sub_123").build();
        when(repository.findByUserId(userId)).thenReturn(Optional.of(active));
        when(repository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));

        Subscription result = svc.changePlan(userId, "SOLO", "YEARLY");

        ArgumentCaptor<ChangePlanCommand> captor = ArgumentCaptor.forClass(ChangePlanCommand.class);
        verify(provider).changeSubscriptionPlan(captor.capture());
        assertThat(captor.getValue().newPriceId()).isEqualTo("price_solo_yearly");
        assertThat(result.getBillingPeriod()).isEqualTo(BillingPeriod.YEARLY);
        assertThat(result.getPlanCode()).isEqualTo(PlanCode.SOLO);
    }

    @Test
    void changePlanRefusesTheYearlyPeriodOnAPlanWithoutAYearlyOfferAndTouchesNothing() {
        UUID userId = UUID.randomUUID();
        BillingProvider provider = mock(BillingProvider.class);
        SubscriptionService svc = serviceWithProvider(provider);

        assertThatThrownBy(() -> svc.changePlan(userId, "PRO", "YEARLY"))
                .isInstanceOf(YearlyBillingUnavailableException.class);

        // Le refus tombe AVANT tout appel au fournisseur : jamais d'abonnement à moitié changé.
        verify(provider, never()).changeSubscriptionPlan(any());
        verify(repository, never()).save(any());
    }

    @Test
    void changePlanRefusesAnUnknownPeriodAndTouchesNothing() {
        UUID userId = UUID.randomUUID();
        BillingProvider provider = mock(BillingProvider.class);
        SubscriptionService svc = serviceWithProvider(provider);

        assertThatThrownBy(() -> svc.changePlan(userId, "SOLO", "WEEKLY"))
                .isInstanceOf(UnknownBillingPeriodException.class);

        verify(provider, never()).changeSubscriptionPlan(any());
        verify(repository, never()).save(any());
    }

    @Test
    void changePlanLeavesTheAtelierOptionAlone() {
        // F-40 / SF-40-02 : l'option est un abonnement à part. Changer de plan ne doit ni la
        // révoquer ni la reconduire — deux engagements, deux cycles de vie.
        UUID userId = UUID.randomUUID();
        BillingProvider provider = mock(BillingProvider.class);
        SubscriptionService svc = serviceWithProvider(provider);
        Subscription optionary = Subscription.builder()
                .userId(userId).status(SubscriptionStatus.ACTIVE).planCode(PlanCode.SOLO)
                .stripeSubscriptionId("sub_plan")
                .atelierOptionStatus(SubscriptionStatus.ACTIVE)
                .atelierOptionStripeSubscriptionId("sub_option")
                .build();
        when(repository.findByUserId(userId)).thenReturn(Optional.of(optionary));
        when(repository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));

        Subscription result = svc.changePlan(userId, "PRO");

        assertThat(result.getPlanCode()).isEqualTo(PlanCode.PRO);
        assertThat(result.getAtelierOptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(result.getAtelierOptionStripeSubscriptionId()).isEqualTo("sub_option");
        // Le changement de plan porte sur l'abonnement du PLAN, jamais sur celui de l'option.
        ArgumentCaptor<ChangePlanCommand> captor = ArgumentCaptor.forClass(ChangePlanCommand.class);
        verify(provider).changeSubscriptionPlan(captor.capture());
        assertThat(captor.getValue().stripeSubscriptionId()).isEqualTo("sub_plan");
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
