package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionService;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.billing.TopUpCatalog;

/**
 * Tests unitaires de l'alerte de consommation (F-42 / SF-42-01). Le cœur de ces tests est
 * l'<b>unicité</b> : la marque se pose une fois, et une seule, par utilisateur et par période.
 * Horloge figée pour rendre la période et les horodatages déterministes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuotaAlertServiceTest {

    @Mock
    private UsageCounterRepository usageCounterRepository;

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private EntitlementService entitlementService;

    private final TopUpCatalog topUpCatalog = new TopUpCatalog();

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    // 2026-07-15 → période attendue : 2026-07-01, période suivante : 2026-08-01.
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-15T10:00:00Z"), ZoneOffset.UTC);
    private final LocalDate period = LocalDate.of(2026, 7, 1);

    private QuotaAlertService service;

    @BeforeEach
    void setUp() {
        service = alertService(new QuotaAlertProperties(null, null));
    }

    private QuotaAlertService alertService(QuotaAlertProperties properties) {
        return new QuotaAlertService(usageCounterRepository, subscriptionService, entitlementService,
                new QuotaWindowService(usageCounterRepository, entitlementService,
                        new fr.claudegateway.billing.BillingProperties(null, null), clock),
                topUpCatalog, properties, clock);
    }

    private void stubQuota(UUID userId, long quota) {
        Subscription sub = Subscription.builder()
                .userId(userId).status(SubscriptionStatus.ACTIVE).planCode(PlanCode.SOLO).build();
        when(subscriptionService.getOrCreateForUser(userId)).thenReturn(sub);
        when(entitlementService.resolveEffectiveMonthlyTokenQuota(sub)).thenReturn(quota);
    }

    private UsageCounter counter(UUID userId, long used, long bonus) {
        return UsageCounter.builder()
                .userId(userId).periodStart(period)
                .inputTokens(used).outputTokens(0L).billedTokens(used).bonusTokens(bonus).build();
    }

    // ---------- Pose de la marque ----------

    @Test
    void raisesAlertWhenThresholdIsCrossed() {
        stubQuota(alice, 1_000_000L);
        UsageCounter counter = counter(alice, 850_000L, 0L);

        service.evaluateAfterUsage(alice, counter);

        assertThat(counter.getQuotaAlertRaisedAt())
                .isEqualTo(OffsetDateTime.now(clock));
    }

    @Test
    void raisesAlertExactlyAtThreshold() {
        stubQuota(alice, 1_000_000L);
        UsageCounter counter = counter(alice, 800_000L, 0L);

        service.evaluateAfterUsage(alice, counter);

        assertThat(counter.getQuotaAlertRaisedAt()).isNotNull();
    }

    @Test
    void doesNotRaiseAlertBelowThreshold() {
        stubQuota(alice, 1_000_000L);
        UsageCounter counter = counter(alice, 799_999L, 0L);

        service.evaluateAfterUsage(alice, counter);

        assertThat(counter.getQuotaAlertRaisedAt()).isNull();
    }

    /**
     * Le test central de la subfeature : une fois posée, la marque n'est jamais réécrite, quel que
     * soit le nombre d'appels qui suivent. Un tour d'agent de trente appels n'émet qu'une alerte.
     */
    @Test
    void raisesAlertOnlyOncePerPeriod() {
        stubQuota(alice, 1_000_000L);
        UsageCounter counter = counter(alice, 850_000L, 0L);

        service.evaluateAfterUsage(alice, counter);
        OffsetDateTime firstRaise = counter.getQuotaAlertRaisedAt();
        assertThat(firstRaise).isNotNull();

        for (int call = 0; call < 30; call++) {
            counter.setInputTokens(counter.getInputTokens() + 1_000L);
            service.evaluateAfterUsage(alice, counter);
        }

        assertThat(counter.getQuotaAlertRaisedAt()).isEqualTo(firstRaise);
    }

    @Test
    void doesNotRaiseAlertWhenEffectiveQuotaIsZero() {
        // Offre BYOK ou abonnement résilié : aucun quota plateforme à approcher.
        stubQuota(alice, 0L);
        UsageCounter counter = counter(alice, 500_000L, 0L);

        service.evaluateAfterUsage(alice, counter);

        assertThat(counter.getQuotaAlertRaisedAt()).isNull();
    }

    @Test
    void countsRedeemedTokensInTheEffectiveQuota() {
        // 850 k consommés sur 1 M d'abonnement + 1 M rachetés = 42,5 % : sous le seuil.
        stubQuota(alice, 1_000_000L);
        UsageCounter counter = counter(alice, 850_000L, 1_000_000L);

        service.evaluateAfterUsage(alice, counter);

        assertThat(counter.getQuotaAlertRaisedAt()).isNull();
    }

    @Test
    void honoursAConfiguredThreshold() {
        stubQuota(alice, 1_000_000L);
        QuotaAlertService halfway = alertService(new QuotaAlertProperties(0.5d, null));
        UsageCounter counter = counter(alice, 500_000L, 0L);

        halfway.evaluateAfterUsage(alice, counter);

        assertThat(counter.getQuotaAlertRaisedAt()).isNotNull();
    }

    // ---------- Lecture de l'alerte ----------

    @Test
    void currentAlertReportsRaisedAlertWithFiguresAndPack() {
        stubQuota(alice, 1_000_000L);
        UsageCounter counter = counter(alice, 850_000L, 0L);
        counter.setQuotaAlertRaisedAt(OffsetDateTime.now(clock));
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, period))
                .thenReturn(Optional.of(counter));

        QuotaAlert alert = service.currentAlert(alice);

        assertThat(alert.raised()).isTrue();
        assertThat(alert.usedTokens()).isEqualTo(850_000L);
        assertThat(alert.quotaTokens()).isEqualTo(1_000_000L);
        assertThat(alert.remainingTokens()).isEqualTo(150_000L);
        assertThat(alert.usedPercent()).isEqualTo(85);
        assertThat(alert.thresholdPercent()).isEqualTo(80);
        assertThat(alert.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(alert.recommendedPack()).isNotNull();
        assertThat(alert.recommendedPack().code()).isEqualTo("STANDARD");
        assertThat(alert.recommendedPack().tokens()).isEqualTo(1_000_000L);
    }

    @Test
    void currentAlertIsNotRaisedOnceDismissed() {
        stubQuota(alice, 1_000_000L);
        UsageCounter counter = counter(alice, 950_000L, 0L);
        counter.setQuotaAlertRaisedAt(OffsetDateTime.now(clock));
        counter.setQuotaAlertDismissedAt(OffsetDateTime.now(clock));
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, period))
                .thenReturn(Optional.of(counter));

        QuotaAlert alert = service.currentAlert(alice);

        // Écartée : plus jamais présentée sur la période, même si la consommation monte encore.
        assertThat(alert.raised()).isFalse();
        assertThat(alert.recommendedPack()).isNull();
        assertThat(alert.usedPercent()).isEqualTo(95);
    }

    @Test
    void currentAlertIsNotRaisedWhenNoCounterExistsForThePeriod() {
        stubQuota(alice, 1_000_000L);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, period))
                .thenReturn(Optional.empty());

        QuotaAlert alert = service.currentAlert(alice);

        assertThat(alert.raised()).isFalse();
        assertThat(alert.usedTokens()).isZero();
        assertThat(alert.quotaTokens()).isEqualTo(1_000_000L);
        assertThat(alert.remainingTokens()).isEqualTo(1_000_000L);
        assertThat(alert.usedPercent()).isZero();
    }

    @Test
    void currentAlertOmitsPackWhenConfiguredCodeIsUnknown() {
        stubQuota(alice, 1_000_000L);
        UsageCounter counter = counter(alice, 850_000L, 0L);
        counter.setQuotaAlertRaisedAt(OffsetDateTime.now(clock));
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, period))
                .thenReturn(Optional.of(counter));

        QuotaAlert alert = alertService(new QuotaAlertProperties(null, "MEGA")).currentAlert(alice);

        // L'alerte informe quand même : seul le bouton de recharge disparaît.
        assertThat(alert.raised()).isTrue();
        assertThat(alert.recommendedPack()).isNull();
    }

    // ---------- Écartement ----------

    @Test
    void dismissStampsTheCounterOfTheCurrentUser() {
        UsageCounter counter = counter(alice, 850_000L, 0L);
        counter.setQuotaAlertRaisedAt(OffsetDateTime.now(clock));
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, period))
                .thenReturn(Optional.of(counter));

        service.dismissCurrentAlert(alice);

        assertThat(counter.getQuotaAlertDismissedAt()).isEqualTo(OffsetDateTime.now(clock));
        verify(usageCounterRepository).save(counter);
    }

    @Test
    void dismissWritesNothingWhenNoAlertIsRaised() {
        UsageCounter counter = counter(alice, 100L, 0L);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, period))
                .thenReturn(Optional.of(counter));

        service.dismissCurrentAlert(alice);

        assertThat(counter.getQuotaAlertDismissedAt()).isNull();
        verify(usageCounterRepository, never()).save(any());
    }

    @Test
    void dismissDoesNotOverwriteAnEarlierDismissal() {
        OffsetDateTime firstDismissal = OffsetDateTime.parse("2026-07-02T08:00:00Z");
        UsageCounter counter = counter(alice, 850_000L, 0L);
        counter.setQuotaAlertRaisedAt(OffsetDateTime.parse("2026-07-02T07:00:00Z"));
        counter.setQuotaAlertDismissedAt(firstDismissal);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, period))
                .thenReturn(Optional.of(counter));

        service.dismissCurrentAlert(alice);

        assertThat(counter.getQuotaAlertDismissedAt()).isEqualTo(firstDismissal);
        verify(usageCounterRepository, never()).save(any());
    }

    // ---------- Isolation utilisateur ----------

    @Test
    void alertIsResolvedPerUserNeverGlobally() {
        stubQuota(alice, 1_000_000L);
        stubQuota(bob, 1_000_000L);
        UsageCounter aliceCounter = counter(alice, 900_000L, 0L);
        aliceCounter.setQuotaAlertRaisedAt(OffsetDateTime.now(clock));
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, period))
                .thenReturn(Optional.of(aliceCounter));
        when(usageCounterRepository.findByUserIdAndPeriodStart(bob, period))
                .thenReturn(Optional.of(counter(bob, 10_000L, 0L)));

        assertThat(service.currentAlert(alice).raised()).isTrue();
        assertThat(service.currentAlert(bob).raised()).isFalse();
    }

    @Test
    void dismissOfOneUserLeavesTheOtherUsersAlertRaised() {
        stubQuota(alice, 1_000_000L);
        UsageCounter aliceCounter = counter(alice, 900_000L, 0L);
        aliceCounter.setQuotaAlertRaisedAt(OffsetDateTime.now(clock));
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, period))
                .thenReturn(Optional.of(aliceCounter));
        when(usageCounterRepository.findByUserIdAndPeriodStart(bob, period))
                .thenReturn(Optional.empty());

        service.dismissCurrentAlert(bob);

        assertThat(aliceCounter.getQuotaAlertDismissedAt()).isNull();
        assertThat(service.currentAlert(alice).raised()).isTrue();
    }

    // ---------- Enveloppe d'essai (F-66) ----------

    /**
     * Un essai commencé le mois dernier et déjà à 85 % de son enveloppe doit être prévenu — même si
     * la ligne du mois courant est presque vide. Sans la fenêtre de quota, le seuil se jugerait sur
     * cette ligne-là et l'utilisateur serait bloqué sans avoir rien vu venir.
     */
    @Test
    void thresholdOfAStraddlingTrialIsJudgedOnTheWholeEnvelope() {
        Subscription trial = Subscription.builder()
                .userId(alice).status(SubscriptionStatus.TRIALING)
                .createdAt(OffsetDateTime.parse("2026-06-28T09:00:00Z"))
                .trialEndsAt(OffsetDateTime.parse("2026-07-12T09:00:00Z"))
                .build();
        when(subscriptionService.getOrCreateForUser(alice)).thenReturn(trial);
        when(entitlementService.hasActiveTrial(trial)).thenReturn(true);
        when(entitlementService.resolveEffectiveMonthlyTokenQuota(trial)).thenReturn(200_000L);
        when(usageCounterRepository.findByUserIdAndPeriodStartGreaterThanEqual(alice,
                LocalDate.of(2026, 6, 1)))
                .thenReturn(java.util.List.of(UsageCounter.builder()
                        .userId(alice).periodStart(LocalDate.of(2026, 6, 1))
                        .inputTokens(160_000L).outputTokens(0L).billedTokens(160_000L).build()));

        UsageCounter july = counter(alice, 10_000L, 0L);
        service.evaluateAfterUsage(alice, july);

        assertThat(july.getQuotaAlertRaisedAt()).isNotNull();
        // Et la bannière annonce le même cumul, sur la fenêtre de l'essai.
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, period))
                .thenReturn(Optional.of(july));
        QuotaAlert alert = service.currentAlert(alice);
        assertThat(alert.usedTokens()).isEqualTo(170_000L);
        assertThat(alert.usedPercent()).isEqualTo(85);
        assertThat(alert.periodEnd()).isEqualTo(LocalDate.of(2026, 7, 12));
    }
}
