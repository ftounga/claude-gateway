package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.billing.BillingProperties;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionStatus;

/**
 * Tests unitaires de la fenêtre de quota (F-66 / SF-66-01). Ce qui se joue ici : un essai n'est pas
 * un mois, et son plafond ne doit pas repartir de zéro parce qu'il traverse un 1er du mois.
 */
@ExtendWith(MockitoExtension.class)
class QuotaWindowServiceTest {

    @Mock
    private UsageCounterRepository usageCounterRepository;

    @Mock
    private EntitlementService entitlementService;

    /** 2026-07-15 → mois courant : 2026-07-01. */
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-15T10:00:00Z"), ZoneOffset.UTC);
    private final LocalDate july = LocalDate.of(2026, 7, 1);
    private final LocalDate june = LocalDate.of(2026, 6, 1);
    private final UUID alice = UUID.randomUUID();

    private QuotaWindowService service;

    @BeforeEach
    void setUp() {
        service = new QuotaWindowService(usageCounterRepository, entitlementService,
                new BillingProperties(null, null), clock);
    }

    private Subscription trial(OffsetDateTime createdAt, OffsetDateTime trialEndsAt) {
        return Subscription.builder()
                .userId(alice)
                .status(SubscriptionStatus.TRIALING)
                .createdAt(createdAt)
                .trialEndsAt(trialEndsAt)
                .build();
    }

    private UsageCounter counter(LocalDate periodStart, long billed, long bonus) {
        return UsageCounter.builder()
                .userId(alice).periodStart(periodStart)
                .inputTokens(billed).outputTokens(0L)
                .billedTokens(billed).bonusTokens(bonus).build();
    }

    @Test
    void paidSubscriptionKeepsTheCalendarMonthAndReadsNothingElse() {
        Subscription active = Subscription.builder()
                .userId(alice).status(SubscriptionStatus.ACTIVE).planCode(PlanCode.SOLO).build();
        when(entitlementService.hasActiveTrial(active)).thenReturn(false);

        QuotaWindow window = service.resolve(active);

        assertThat(window.periodStart()).isEqualTo(july);
        assertThat(window.displayStart()).isEqualTo(july);
        assertThat(window.displayEnd()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(window.carryOverBilledTokens()).isZero();
        assertThat(window.carryOverBonusTokens()).isZero();
        // Aucune lecture multi-période : le mois suffit, et c'est le cas courant.
        verify(usageCounterRepository, never()).findByUserIdAndPeriodStartGreaterThanEqual(any(), any());
    }

    @Test
    void trialStartedThisMonthStaysWithinTheMonthWithoutCarryOver() {
        Subscription subscription = trial(
                OffsetDateTime.parse("2026-07-10T08:00:00Z"),
                OffsetDateTime.parse("2026-07-24T08:00:00Z"));
        when(entitlementService.hasActiveTrial(subscription)).thenReturn(true);

        QuotaWindow window = service.resolve(subscription);

        assertThat(window.periodStart()).isEqualTo(july);
        assertThat(window.displayStart()).isEqualTo(LocalDate.of(2026, 7, 10));
        assertThat(window.displayEnd()).isEqualTo(LocalDate.of(2026, 7, 24));
        assertThat(window.carryOverBilledTokens()).isZero();
        verify(usageCounterRepository, never()).findByUserIdAndPeriodStartGreaterThanEqual(any(), any());
    }

    @Test
    void trialStartedLastMonthCarriesOverWhatThatMonthConsumed() {
        Subscription subscription = trial(
                OffsetDateTime.parse("2026-06-25T08:00:00Z"),
                OffsetDateTime.parse("2026-07-09T08:00:00Z"));
        when(entitlementService.hasActiveTrial(subscription)).thenReturn(true);
        when(usageCounterRepository.findByUserIdAndPeriodStartGreaterThanEqual(alice, june))
                .thenReturn(List.of(counter(june, 120_000L, 5_000L), counter(july, 30_000L, 0L)));

        QuotaWindow window = service.resolve(subscription);

        assertThat(window.periodStart()).isEqualTo(june);
        assertThat(window.displayStart()).isEqualTo(LocalDate.of(2026, 6, 25));
        assertThat(window.displayEnd()).isEqualTo(LocalDate.of(2026, 7, 9));
        // Le mois courant est lu par l'appelant sur sa ligne vivante : le report ne compte QUE juin,
        // sans quoi la consommation de juillet serait comptée deux fois.
        assertThat(window.carryOverBilledTokens()).isEqualTo(120_000L);
        assertThat(window.carryOverProcessedTokens()).isEqualTo(120_000L);
        assertThat(window.carryOverBonusTokens()).isEqualTo(5_000L);
    }

    @Test
    void trialOnALongLivedSubscriptionNeverWalksBackFurtherThanLastMonth() {
        // Abonnement créé il y a un an qui se retrouve en essai : sans le bornage, la fenêtre
        // remonterait à 2025 et lirait une année de compteurs.
        Subscription subscription = trial(
                OffsetDateTime.parse("2025-07-01T08:00:00Z"),
                OffsetDateTime.parse("2026-07-20T08:00:00Z"));
        when(entitlementService.hasActiveTrial(subscription)).thenReturn(true);
        when(usageCounterRepository.findByUserIdAndPeriodStartGreaterThanEqual(alice, june))
                .thenReturn(List.of(counter(june, 1_000L, 0L)));

        QuotaWindow window = service.resolve(subscription);

        assertThat(window.periodStart()).isEqualTo(june);
        assertThat(window.displayStart()).isEqualTo(june);
        assertThat(window.carryOverBilledTokens()).isEqualTo(1_000L);
    }

    @Test
    void trialWithoutCreationDateFallsBackOnTheEndMinusTheConfiguredDuration() {
        // Durée par défaut : 5 jours → essai réputé commencé le 2026-06-29, donc fenêtre sur juin.
        Subscription subscription = trial(null, OffsetDateTime.parse("2026-07-04T08:00:00Z"));
        when(entitlementService.hasActiveTrial(subscription)).thenReturn(true);
        when(usageCounterRepository.findByUserIdAndPeriodStartGreaterThanEqual(eq(alice), eq(june)))
                .thenReturn(List.of(counter(june, 10_000L, 0L)));

        QuotaWindow window = service.resolve(subscription);

        assertThat(window.periodStart()).isEqualTo(june);
        assertThat(window.displayStart()).isEqualTo(LocalDate.of(2026, 6, 29));
        assertThat(window.carryOverBilledTokens()).isEqualTo(10_000L);
    }

    @Test
    void trialWithoutAnyEndDateShowsTheMonthBoundRatherThanInventingOne() {
        Subscription subscription = trial(OffsetDateTime.parse("2026-07-02T08:00:00Z"), null);
        when(entitlementService.hasActiveTrial(subscription)).thenReturn(true);

        QuotaWindow window = service.resolve(subscription);

        assertThat(window.displayStart()).isEqualTo(LocalDate.of(2026, 7, 2));
        // Aucune échéance enregistrée : on n'invente pas de date de fin, on rend la borne du mois.
        assertThat(window.displayEnd()).isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    void trialWithoutAnyDateFallsBackOnTheCurrentMonth() {
        Subscription subscription = trial(null, null);
        when(entitlementService.hasActiveTrial(subscription)).thenReturn(true);

        QuotaWindow window = service.resolve(subscription);

        assertThat(window.periodStart()).isEqualTo(july);
        assertThat(window.carryOverBilledTokens()).isZero();
    }

    @Test
    void trialStartingInTheFutureNeverProducesAnEmptyWindow() {
        Subscription subscription = trial(
                OffsetDateTime.parse("2026-09-01T08:00:00Z"),
                OffsetDateTime.parse("2026-09-15T08:00:00Z"));
        when(entitlementService.hasActiveTrial(subscription)).thenReturn(true);

        QuotaWindow window = service.resolve(subscription);

        assertThat(window.periodStart()).isEqualTo(july);
        assertThat(window.displayStart()).isEqualTo(july);
    }

    @Test
    void nullSubscriptionResolvesToTheCurrentMonth() {
        when(entitlementService.hasActiveTrial(null)).thenReturn(false);

        assertThat(service.resolve(null).periodStart()).isEqualTo(july);
    }
}
