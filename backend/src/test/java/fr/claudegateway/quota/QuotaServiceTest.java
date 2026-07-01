package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionService;
import fr.claudegateway.billing.SubscriptionStatus;

/**
 * Tests unitaires du contrôle de quota (SF-10-01) : pré-contrôle, enregistrement de consommation
 * (upsert par période) et instantané d'usage. Horloge figée pour rendre la période déterministe.
 */
@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    @Mock
    private UsageCounterRepository usageCounterRepository;

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private EntitlementService entitlementService;

    private QuotaService quotaService;

    private final UUID alice = UUID.randomUUID();
    // 2026-07-15 → période attendue : 2026-07-01.
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-15T10:00:00Z"), ZoneOffset.UTC);
    private final LocalDate expectedPeriod = LocalDate.of(2026, 7, 1);

    @BeforeEach
    void setUp() {
        quotaService = new QuotaService(usageCounterRepository, subscriptionService,
                entitlementService, clock);
    }

    private void stubQuota(long quota) {
        Subscription sub = Subscription.builder()
                .userId(alice).status(SubscriptionStatus.ACTIVE).planCode(PlanCode.PRO).build();
        when(subscriptionService.getOrCreateForUser(alice)).thenReturn(sub);
        when(entitlementService.resolveMonthlyTokenQuota(sub)).thenReturn(quota);
    }

    private UsageCounter counter(long input, long output) {
        return UsageCounter.builder()
                .userId(alice).periodStart(expectedPeriod)
                .inputTokens(input).outputTokens(output).build();
    }

    @Test
    void assertWithinQuotaPassesWhenBelowLimit() {
        stubQuota(1_000L);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.of(counter(500, 200)));

        assertThatCode(() -> quotaService.assertWithinQuota(alice)).doesNotThrowAnyException();
    }

    @Test
    void assertWithinQuotaThrowsWhenAtLimit() {
        stubQuota(700L);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.of(counter(500, 200))); // total 700 == quota

        assertThatThrownBy(() -> quotaService.assertWithinQuota(alice))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void assertWithinQuotaThrowsWhenQuotaIsZero() {
        stubQuota(0L);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> quotaService.assertWithinQuota(alice))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void recordUsageCreatesCounterOnFirstCall() {
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.empty());
        when(usageCounterRepository.save(any(UsageCounter.class))).thenAnswer(inv -> inv.getArgument(0));

        quotaService.recordUsage(alice, 42, 17);

        ArgumentCaptor<UsageCounter> captor = ArgumentCaptor.forClass(UsageCounter.class);
        // save appelé deux fois : création (0/0) puis incrément ; on vérifie l'état final.
        verify(usageCounterRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        UsageCounter last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.getUserId()).isEqualTo(alice);
        assertThat(last.getPeriodStart()).isEqualTo(expectedPeriod);
        assertThat(last.getInputTokens()).isEqualTo(42);
        assertThat(last.getOutputTokens()).isEqualTo(17);
    }

    @Test
    void recordUsageIncrementsExistingCounter() {
        UsageCounter existing = counter(100, 50);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.of(existing));
        when(usageCounterRepository.save(any(UsageCounter.class))).thenAnswer(inv -> inv.getArgument(0));

        quotaService.recordUsage(alice, 10, 5);

        assertThat(existing.getInputTokens()).isEqualTo(110);
        assertThat(existing.getOutputTokens()).isEqualTo(55);
    }

    @Test
    void recordUsageIgnoresZeroConsumption() {
        quotaService.recordUsage(alice, 0, 0);
        verify(usageCounterRepository, never()).save(any());
    }

    @Test
    void currentUsageComputesRemainingAndPeriodBounds() {
        stubQuota(1_000L);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.of(counter(300, 100)));

        UsageSnapshot snapshot = quotaService.currentUsage(alice);

        assertThat(snapshot.usedTokens()).isEqualTo(400);
        assertThat(snapshot.quotaTokens()).isEqualTo(1_000);
        assertThat(snapshot.remainingTokens()).isEqualTo(600);
        assertThat(snapshot.periodStart()).isEqualTo(expectedPeriod);
        assertThat(snapshot.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    void currentUsageClampsRemainingToZeroWhenOver() {
        stubQuota(500L);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.of(counter(400, 300))); // 700 > 500

        UsageSnapshot snapshot = quotaService.currentUsage(alice);

        assertThat(snapshot.usedTokens()).isEqualTo(700);
        assertThat(snapshot.remainingTokens()).isZero();
    }
}
