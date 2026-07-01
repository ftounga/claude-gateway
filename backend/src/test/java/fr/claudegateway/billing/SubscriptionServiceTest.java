package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        service = new SubscriptionService(repository, new BillingProperties(14));
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
        SubscriptionService sevenDayService = new SubscriptionService(repository, new BillingProperties(7));
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());
        when(repository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));

        Subscription result = sevenDayService.getOrCreateForUser(userId);

        assertThat(result.getTrialEndsAt()).isAfter(OffsetDateTime.now().plusDays(6));
        assertThat(result.getTrialEndsAt()).isBefore(OffsetDateTime.now().plusDays(8));
        verify(repository, times(1)).save(any());
    }

    @Test
    void defaultsTrialDaysWhenPropertyInvalid() {
        assertThat(new BillingProperties(null).trialDays()).isEqualTo(14);
        assertThat(new BillingProperties(0).trialDays()).isEqualTo(14);
        assertThat(new BillingProperties(-3).trialDays()).isEqualTo(14);
    }
}
