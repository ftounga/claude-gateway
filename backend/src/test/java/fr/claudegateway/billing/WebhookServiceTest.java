package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fr.claudegateway.billing.provider.BillingEvent;
import fr.claudegateway.billing.provider.BillingEventType;
import fr.claudegateway.billing.provider.BillingProvider;
import fr.claudegateway.quota.QuotaService;

/** Tests unitaires de l'application des événements de facturation (SF-09-02 + SF-21-02 top-up). */
class WebhookServiceTest {

    private BillingProvider provider;
    private SubscriptionRepository repository;
    private QuotaService quotaService;
    private ProcessedBillingEventRepository processedEventRepository;
    private WebhookService service;

    @BeforeEach
    void setUp() {
        provider = mock(BillingProvider.class);
        repository = mock(SubscriptionRepository.class);
        quotaService = mock(QuotaService.class);
        processedEventRepository = mock(ProcessedBillingEventRepository.class);
        service = new WebhookService(
                provider, repository, quotaService, new TopUpCatalog(), processedEventRepository);
    }

    private Subscription trialFor(UUID userId) {
        return Subscription.builder()
                .userId(userId).status(SubscriptionStatus.TRIALING)
                .trialEndsAt(OffsetDateTime.now().plusDays(14)).build();
    }

    @Test
    void checkoutCompletedActivatesSubscription() {
        UUID userId = UUID.randomUUID();
        Subscription sub = trialFor(userId);
        when(repository.findByUserId(userId)).thenReturn(Optional.of(sub));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(provider.parseWebhookEvent("p", "s")).thenReturn(new BillingEvent(
                BillingEventType.CHECKOUT_COMPLETED, userId, "cus_1", "sub_1",
                PlanCode.PRO, "active", null, "evt_1", null));

        service.handle("p", "s");

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getPlanCode()).isEqualTo(PlanCode.PRO);
        assertThat(sub.getStripeCustomerId()).isEqualTo("cus_1");
        assertThat(sub.getStripeSubscriptionId()).isEqualTo("sub_1");
    }

    @Test
    void checkoutCompletedForDayPassSetsOneDayPeriod() {
        UUID userId = UUID.randomUUID();
        Subscription sub = trialFor(userId);
        when(repository.findByUserId(userId)).thenReturn(Optional.of(sub));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(provider.parseWebhookEvent("p", "s")).thenReturn(new BillingEvent(
                BillingEventType.CHECKOUT_COMPLETED, userId, "cus_1", null,
                PlanCode.DAILY, "active", null, "evt_1", null));

        service.handle("p", "s");

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getCurrentPeriodEnd()).isAfter(OffsetDateTime.now().plusHours(23));
        assertThat(sub.getCurrentPeriodEnd()).isBefore(OffsetDateTime.now().plusHours(25));
    }

    @Test
    void subscriptionDeletedCancels() {
        Subscription sub = trialFor(UUID.randomUUID());
        sub.setStripeSubscriptionId("sub_9");
        sub.setStatus(SubscriptionStatus.ACTIVE);
        when(repository.findByStripeSubscriptionId("sub_9")).thenReturn(Optional.of(sub));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(provider.parseWebhookEvent("p", "s")).thenReturn(new BillingEvent(
                BillingEventType.SUBSCRIPTION_DELETED, null, null, "sub_9", null, "canceled", null,
                "evt_1", null));

        service.handle("p", "s");

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
    }

    @Test
    void ignoresEventWhenSubscriptionNotFound() {
        when(provider.parseWebhookEvent("p", "s")).thenReturn(new BillingEvent(
                BillingEventType.CHECKOUT_COMPLETED, UUID.randomUUID(), "cus_x", "sub_x",
                PlanCode.PRO, "active", null, "evt_1", null));
        when(repository.findByStripeSubscriptionId(any())).thenReturn(Optional.empty());
        when(repository.findByStripeCustomerId(any())).thenReturn(Optional.empty());
        when(repository.findByUserId(any())).thenReturn(Optional.empty());

        service.handle("p", "s");

        verify(repository, never()).save(any());
    }

    @Test
    void ignoresUnhandledEvent() {
        when(provider.parseWebhookEvent("p", "s")).thenReturn(BillingEvent.unhandled());

        service.handle("p", "s");

        verify(repository, never()).save(any());
    }

    @Test
    void isIdempotentOnRepeatedDeletion() {
        Subscription sub = trialFor(UUID.randomUUID());
        sub.setStripeSubscriptionId("sub_9");
        when(repository.findByStripeSubscriptionId("sub_9")).thenReturn(Optional.of(sub));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        BillingEvent evt = new BillingEvent(
                BillingEventType.SUBSCRIPTION_DELETED, null, null, "sub_9", null, "canceled", null,
                "evt_1", null);
        when(provider.parseWebhookEvent("p", "s")).thenReturn(evt);

        service.handle("p", "s");
        service.handle("p", "s");

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
    }

    // --- Rachat de tokens (top-up, SF-21-02) ---

    private BillingEvent topUpEvent(UUID userId, String eventId, String topupCode) {
        return new BillingEvent(
                BillingEventType.TOPUP_COMPLETED, userId, "cus_1", null, null, null, null,
                eventId, topupCode);
    }

    @Test
    void topUpCompletedCreditsBonusTokens() {
        UUID userId = UUID.randomUUID();
        when(processedEventRepository.existsById("evt_top")).thenReturn(false);
        when(provider.parseWebhookEvent("p", "s")).thenReturn(topUpEvent(userId, "evt_top", "STANDARD"));

        service.handle("p", "s");

        // Le pack STANDARD du catalogue crédite 1 000 000 tokens à l'utilisateur ciblé.
        verify(quotaService).creditBonusTokens(userId, 1_000_000L);
        verify(processedEventRepository).saveAndFlush(any(ProcessedBillingEvent.class));
    }

    @Test
    void topUpIsIdempotentOnReplayedEvent() {
        UUID userId = UUID.randomUUID();
        // Premier passage : non traité ; second passage : déjà présent.
        when(processedEventRepository.existsById("evt_top")).thenReturn(false, true);
        when(provider.parseWebhookEvent("p", "s")).thenReturn(topUpEvent(userId, "evt_top", "STANDARD"));

        service.handle("p", "s");
        service.handle("p", "s");

        // Un seul crédit malgré deux livraisons du même événement.
        verify(quotaService, times(1)).creditBonusTokens(eq(userId), any(Long.class));
    }

    @Test
    void topUpIgnoredWhenUserMissing() {
        when(provider.parseWebhookEvent("p", "s")).thenReturn(topUpEvent(null, "evt_top", "STANDARD"));

        service.handle("p", "s");

        verify(quotaService, never()).creditBonusTokens(any(), any(Long.class));
        verify(processedEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void topUpIgnoredWhenPackUnknown() {
        UUID userId = UUID.randomUUID();
        when(provider.parseWebhookEvent("p", "s")).thenReturn(topUpEvent(userId, "evt_top", "GHOST"));

        service.handle("p", "s");

        verify(quotaService, never()).creditBonusTokens(any(), any(Long.class));
        verify(processedEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void topUpIgnoredWhenEventIdMissing() {
        UUID userId = UUID.randomUUID();
        when(provider.parseWebhookEvent("p", "s")).thenReturn(topUpEvent(userId, null, "STANDARD"));

        service.handle("p", "s");

        verify(quotaService, never()).creditBonusTokens(any(), any(Long.class));
        verify(processedEventRepository, never()).saveAndFlush(any());
    }
}
