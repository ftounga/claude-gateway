package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fr.claudegateway.billing.provider.BillingEvent;
import fr.claudegateway.billing.provider.BillingEventType;
import fr.claudegateway.billing.provider.BillingProvider;

/** Tests unitaires de l'application des événements de facturation (SF-09-02). */
class WebhookServiceTest {

    private BillingProvider provider;
    private SubscriptionRepository repository;
    private WebhookService service;

    @BeforeEach
    void setUp() {
        provider = mock(BillingProvider.class);
        repository = mock(SubscriptionRepository.class);
        service = new WebhookService(provider, repository);
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
                PlanCode.PRO, "active", null));

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
                PlanCode.DAILY, "active", null));

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
                BillingEventType.SUBSCRIPTION_DELETED, null, null, "sub_9", null, "canceled", null));

        service.handle("p", "s");

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
    }

    @Test
    void ignoresEventWhenSubscriptionNotFound() {
        when(provider.parseWebhookEvent("p", "s")).thenReturn(new BillingEvent(
                BillingEventType.CHECKOUT_COMPLETED, UUID.randomUUID(), "cus_x", "sub_x",
                PlanCode.PRO, "active", null));
        when(repository.findByStripeSubscriptionId(any())).thenReturn(Optional.empty());
        when(repository.findByStripeCustomerId(any())).thenReturn(Optional.empty());
        when(repository.findByUserId(any())).thenReturn(Optional.empty());

        service.handle("p", "s");

        org.mockito.Mockito.verify(repository, never()).save(any());
    }

    @Test
    void ignoresUnhandledEvent() {
        when(provider.parseWebhookEvent("p", "s")).thenReturn(BillingEvent.unhandled());

        service.handle("p", "s");

        org.mockito.Mockito.verify(repository, never()).save(any());
    }

    @Test
    void isIdempotentOnRepeatedDeletion() {
        Subscription sub = trialFor(UUID.randomUUID());
        sub.setStripeSubscriptionId("sub_9");
        when(repository.findByStripeSubscriptionId("sub_9")).thenReturn(Optional.of(sub));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        BillingEvent evt = new BillingEvent(
                BillingEventType.SUBSCRIPTION_DELETED, null, null, "sub_9", null, "canceled", null);
        when(provider.parseWebhookEvent("p", "s")).thenReturn(evt);

        service.handle("p", "s");
        service.handle("p", "s");

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
    }
}
