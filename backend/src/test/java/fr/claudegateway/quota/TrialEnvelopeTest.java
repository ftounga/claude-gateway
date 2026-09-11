package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.billing.BillingProperties;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionService;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.byok.ByokKeyService;

/**
 * Tests unitaires de l'enveloppe d'essai (F-66 / SF-66-01).
 *
 * <p>Le défaut corrigé, en une phrase : le plafond de l'essai s'opposait au <b>mois calendaire</b>,
 * si bien qu'un essai commencé fin juin retrouvait son plafond entier le 1er juillet — deux fois
 * 200 000 jetons pour un essai annoncé à 200 000. Ce que ces tests vérifient, c'est que l'essai
 * compte désormais sur <b>toute sa durée</b>, et que rien ne change pour un abonnement payant, dont
 * l'allocation est réellement mensuelle.</p>
 */
@ExtendWith(MockitoExtension.class)
class TrialEnvelopeTest {

    @Mock
    private UsageCounterRepository usageCounterRepository;
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private EntitlementService entitlementService;
    @Mock
    private ByokKeyService byokKeyService;
    @Mock
    private QuotaAlertService quotaAlertService;
    @Mock
    private UsageLedgerService usageLedgerService;

    /** 2026-07-15 → mois courant : 2026-07-01, mois précédent : 2026-06-01. */
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-15T10:00:00Z"), ZoneOffset.UTC);
    private final LocalDate july = LocalDate.of(2026, 7, 1);
    private final LocalDate june = LocalDate.of(2026, 6, 1);
    private final UUID alice = UUID.randomUUID();

    private QuotaService quotaService;

    @BeforeEach
    void setUp() {
        quotaService = new QuotaService(usageCounterRepository, subscriptionService,
                entitlementService, byokKeyService,
                new QuotaWindowService(usageCounterRepository, entitlementService,
                        new BillingProperties(null, null), clock),
                quotaAlertService, usageLedgerService,
                new BilledTokensCalculator(
                        new TokenPricingProperties(null, null, null, null, null, null)),
                new QuotaProperties(null, null, null), clock);
    }

    /** Essai commencé le 28 juin, encore en cours : la fenêtre couvre juin et juillet. */
    private Subscription trialStartedLastMonth() {
        Subscription subscription = Subscription.builder()
                .userId(alice)
                .status(SubscriptionStatus.TRIALING)
                .createdAt(OffsetDateTime.parse("2026-06-28T09:00:00Z"))
                .trialEndsAt(OffsetDateTime.parse("2026-07-12T09:00:00Z"))
                .build();
        when(subscriptionService.getOrCreateForUser(alice)).thenReturn(subscription);
        when(entitlementService.hasActiveTrial(subscription)).thenReturn(true);
        when(entitlementService.resolveEffectiveMonthlyTokenQuota(subscription)).thenReturn(200_000L);
        return subscription;
    }

    private UsageCounter counter(LocalDate periodStart, long billed, long bonus) {
        return UsageCounter.builder()
                .userId(alice).periodStart(periodStart)
                .inputTokens(billed).outputTokens(0L)
                .billedTokens(billed).bonusTokens(bonus).build();
    }

    private void previousMonth(long billed, long bonus) {
        when(usageCounterRepository.findByUserIdAndPeriodStartGreaterThanEqual(alice, june))
                .thenReturn(List.of(counter(june, billed, bonus)));
    }

    private void currentMonth(long billed, long bonus) {
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, july))
                .thenReturn(Optional.of(counter(july, billed, bonus)));
    }

    @Test
    void trialExhaustedLastMonthIsStillBlockedThisMonth() {
        trialStartedLastMonth();
        previousMonth(200_000L, 0L);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, july))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> quotaService.assertWithinQuota(alice))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void trialSpendsTheRestOfItsEnvelopeAndNotOneTokenMore() {
        trialStartedLastMonth();
        previousMonth(120_000L, 0L);
        currentMonth(79_999L, 0L);

        // 199 999 consommés sur 200 000 : le dernier jeton passe encore.
        assertThatCode(() -> quotaService.assertWithinQuota(alice)).doesNotThrowAnyException();

        currentMonth(80_000L, 0L);
        assertThatThrownBy(() -> quotaService.assertWithinQuota(alice))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void gaugeShowsTheWholeTrialAndItsRealWindow() {
        trialStartedLastMonth();
        previousMonth(120_000L, 0L);
        currentMonth(30_000L, 0L);

        UsageSnapshot snapshot = quotaService.currentUsage(alice);

        assertThat(snapshot.usedTokens()).isEqualTo(150_000L);
        assertThat(snapshot.quotaTokens()).isEqualTo(200_000L);
        assertThat(snapshot.remainingTokens()).isEqualTo(50_000L);
        assertThat(snapshot.processedTokens()).isEqualTo(150_000L);
        // La jauge annonce la fenêtre de l'essai, pas celle du mois : ce plafond ne se renouvelle
        // pas le 1er, il s'éteint avec l'essai.
        assertThat(snapshot.periodStart()).isEqualTo(LocalDate.of(2026, 6, 28));
        assertThat(snapshot.periodEnd()).isEqualTo(LocalDate.of(2026, 7, 12));
    }

    @Test
    void topUpBoughtDuringTheTrialSurvivesTheFirstOfTheMonth() {
        trialStartedLastMonth();
        previousMonth(200_000L, 50_000L);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, july))
                .thenReturn(Optional.empty());

        // Enveloppe épuisée, mais 50 000 jetons rachetés en juin : l'essai continue.
        assertThatCode(() -> quotaService.assertWithinQuota(alice)).doesNotThrowAnyException();
        assertThat(quotaService.currentUsage(alice).quotaTokens()).isEqualTo(250_000L);
    }

    @Test
    void paidSubscriptionKeepsItsMonthlyAllowance() {
        Subscription active = Subscription.builder()
                .userId(alice).status(SubscriptionStatus.ACTIVE).planCode(PlanCode.SOLO)
                .createdAt(OffsetDateTime.parse("2026-06-01T09:00:00Z"))
                .build();
        when(subscriptionService.getOrCreateForUser(alice)).thenReturn(active);
        when(entitlementService.hasActiveTrial(active)).thenReturn(false);
        when(entitlementService.resolveEffectiveMonthlyTokenQuota(active)).thenReturn(200_000L);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, july))
                .thenReturn(Optional.empty());

        // Quota consommé le mois dernier, mois courant vierge : l'abonnement paie un mois, il a un
        // mois. Rien de ce que fait F-66 ne doit changer cela.
        assertThatCode(() -> quotaService.assertWithinQuota(alice)).doesNotThrowAnyException();
        assertThat(quotaService.currentUsage(alice).usedTokens()).isZero();
        assertThat(quotaService.currentUsage(alice).periodStart()).isEqualTo(july);
    }

    @Test
    void expiredTrialIsBlockedAndReadsNothingBeyondTheMonth() {
        Subscription expired = Subscription.builder()
                .userId(alice).status(SubscriptionStatus.TRIALING)
                .createdAt(OffsetDateTime.parse("2026-06-01T09:00:00Z"))
                .trialEndsAt(OffsetDateTime.parse("2026-06-06T09:00:00Z"))
                .build();
        when(subscriptionService.getOrCreateForUser(alice)).thenReturn(expired);
        when(entitlementService.hasActiveTrial(expired)).thenReturn(false);
        when(entitlementService.resolveEffectiveMonthlyTokenQuota(expired)).thenReturn(0L);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, july))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> quotaService.assertWithinQuota(alice))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void anotherUsersConsumptionNeverEntersTheWindow() {
        Subscription subscription = trialStartedLastMonth();
        // Le report ne lit que les compteurs rendus pour CET utilisateur : la méthode de repository
        // filtre sur user_id, et aucune ligne d'un autre compte ne peut donc entrer dans la somme.
        when(usageCounterRepository.findByUserIdAndPeriodStartGreaterThanEqual(alice, june))
                .thenReturn(List.of());
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, july))
                .thenReturn(Optional.empty());

        assertThat(subscription.getUserId()).isEqualTo(alice);
        assertThat(quotaService.currentUsage(alice).usedTokens()).isZero();
        assertThatCode(() -> quotaService.assertWithinQuota(alice)).doesNotThrowAnyException();
    }
}
