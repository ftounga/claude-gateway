package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionService;

/**
 * Tests unitaires du rachat de tokens dans le quota (F-21 / SF-21-01) : le bonus de la période élargit
 * le quota effectif, {@code creditBonusTokens} l'incrémente, et l'instantané le reflète.
 */
@ExtendWith(MockitoExtension.class)
class QuotaBonusTest {

    @Mock
    private UsageCounterRepository usageCounterRepository;
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private EntitlementService entitlementService;
    @Mock
    private Subscription subscription;

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-15T10:00:00Z"), ZoneOffset.UTC);
    private final LocalDate period = LocalDate.of(2026, 7, 1);
    private final UUID alice = UUID.randomUUID();

    private QuotaService quotaService;

    @BeforeEach
    void setUp() {
        quotaService = new QuotaService(usageCounterRepository, subscriptionService, entitlementService, clock);
    }

    private void baseQuota(long quota) {
        when(subscriptionService.getOrCreateForUser(alice)).thenReturn(subscription);
        when(entitlementService.resolveMonthlyTokenQuota(subscription)).thenReturn(quota);
    }

    private UsageCounter counter(long input, long output, long bonus) {
        return UsageCounter.builder()
                .userId(alice).periodStart(period)
                .inputTokens(input).outputTokens(output).bonusTokens(bonus).build();
    }

    @Test
    void bonusRaisesEffectiveQuotaBeyondSubscriptionLimit() {
        baseQuota(100);
        // Utilisateur à sa limite d'abonnement (100 consommés) mais 50 tokens rachetés → autorisé.
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, period))
                .thenReturn(Optional.of(counter(60, 40, 50)));

        assertThatCode(() -> quotaService.assertWithinQuota(alice)).doesNotThrowAnyException();
    }

    @Test
    void withoutBonusReachingLimitIsBlocked() {
        baseQuota(100);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, period))
                .thenReturn(Optional.of(counter(60, 40, 0)));

        assertThatThrownBy(() -> quotaService.assertWithinQuota(alice))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void creditBonusTokensIncrementsCurrentPeriodBonus() {
        UsageCounter existing = counter(10, 5, 20);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, period))
                .thenReturn(Optional.of(existing));
        when(usageCounterRepository.save(any(UsageCounter.class))).thenAnswer(inv -> inv.getArgument(0));

        quotaService.creditBonusTokens(alice, 30);

        ArgumentCaptor<UsageCounter> captor = ArgumentCaptor.forClass(UsageCounter.class);
        verify(usageCounterRepository).save(captor.capture());
        assertThat(captor.getValue().getBonusTokens()).isEqualTo(50L);
    }

    @Test
    void creditIgnoresNonPositiveAmounts() {
        quotaService.creditBonusTokens(alice, 0);
        verify(usageCounterRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void snapshotQuotaIncludesBonus() {
        baseQuota(100);
        when(usageCounterRepository.findByUserIdAndPeriodStart(eq(alice), eq(period)))
                .thenReturn(Optional.of(counter(10, 10, 50)));

        UsageSnapshot snapshot = quotaService.currentUsage(alice);

        assertThat(snapshot.quotaTokens()).isEqualTo(150L);
        assertThat(snapshot.usedTokens()).isEqualTo(20L);
        assertThat(snapshot.remainingTokens()).isEqualTo(130L);
    }
}
