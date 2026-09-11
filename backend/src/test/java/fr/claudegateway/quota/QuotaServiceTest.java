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
import fr.claudegateway.byok.ByokKeyRequiredException;
import fr.claudegateway.byok.ByokKeyService;

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

    @Mock
    private ByokKeyService byokKeyService;

    @Mock
    private QuotaAlertService quotaAlertService;

    private QuotaService quotaService;

    private final UUID alice = UUID.randomUUID();
    // 2026-07-15 → période attendue : 2026-07-01.
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-15T10:00:00Z"), ZoneOffset.UTC);
    private final LocalDate expectedPeriod = LocalDate.of(2026, 7, 1);
    // Plafond de bac à sable figé à 100 s pour rendre les tests de gate déterministes.
    private final QuotaProperties quotaProperties = new QuotaProperties(null, null, 100L);

    /**
     * Calculateur de décompte au coût réel (F-63), utilisé <b>réel</b> : c'est la pondération qu'on
     * veut voir agir, la mocker reviendrait à tester le décompte contre lui-même.
     */
    private final BilledTokensCalculator billedTokensCalculator = new BilledTokensCalculator(
            new TokenPricingProperties(null, null, null, null, null, null));

    /** Journal par tour (F-61) : présent, mais muet ici — ce test juge le quota, pas le relevé. */
    private final UsageLedgerService usageLedgerService =
            org.mockito.Mockito.mock(UsageLedgerService.class);

    @BeforeEach
    void setUp() {
        quotaService = new QuotaService(usageCounterRepository, subscriptionService,
                entitlementService, byokKeyService, quotaAlertService, usageLedgerService,
                billedTokensCalculator, quotaProperties, clock);
    }

    private void stubQuota(long quota) {
        Subscription sub = Subscription.builder()
                .userId(alice).status(SubscriptionStatus.ACTIVE).planCode(PlanCode.PRO).build();
        when(subscriptionService.getOrCreateForUser(alice)).thenReturn(sub);
        when(entitlementService.resolveMonthlyTokenQuota(sub)).thenReturn(quota);
    }

    /**
     * Compteur de période dont le décompte FACTURÉ (F-63) vaut le volume traité — l'état exact
     * d'une ligne reprise par la migration 069, et celui d'avant F-63 où toutes les natures de
     * tokens pesaient pareil. C'est le décompte facturé que le quota oppose.
     */
    private UsageCounter counter(long input, long output) {
        return UsageCounter.builder()
                .userId(alice).periodStart(expectedPeriod)
                .inputTokens(input).outputTokens(output).billedTokens(input + output).build();
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

    // ---------------------------------------------------------------------------------------------
    // F-63 / SF-63-01 — le quota compte au coût réel : chaque nature de token pèse le sien.
    // ---------------------------------------------------------------------------------------------

    @Test
    void theBilledCountWeighsEachNatureWhileTheVolumesStayVolumes() {
        UsageCounter existing = counter(0, 0);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.of(existing));
        when(usageCounterRepository.save(any(UsageCounter.class))).thenAnswer(inv -> inv.getArgument(0));

        // 9 000 tokens d'entrée et 9 000 de sortie : même volume, cinq fois le coût côté sortie.
        quotaService.recordUsage(alice, new TurnTokens(9_000L, 9_000L, 0L, 0L), null, null, null);

        // Les VOLUMES sont exactement ce qu'ils étaient avant F-63 : F-16 et F-61 n'en voient aucun
        // changement.
        assertThat(existing.getInputTokens()).isEqualTo(9_000L);
        assertThat(existing.getOutputTokens()).isEqualTo(9_000L);
        // Le DÉCOMPTE, lui, pèse la sortie à son prix : 5 000 + 25 000 au lieu de 18 000.
        assertThat(existing.getBilledTokens()).isEqualTo(30_000L);
    }

    @Test
    void cacheTokensCountAsVolumeOnInputButAreBilledAtTheirOwnPrice() {
        UsageCounter existing = counter(0, 0);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.of(existing));
        when(usageCounterRepository.save(any(UsageCounter.class))).thenAnswer(inv -> inv.getArgument(0));

        quotaService.recordUsage(alice, new TurnTokens(0L, 0L, 100_000L, 0L), null, null, null);

        // Volume : 100 000 tokens traités, comme l'ont toujours compté les fournisseurs (SF-39-01).
        assertThat(existing.getInputTokens()).isEqualTo(100_000L);
        // Décompte : un dixième du tarif d'entrée. Les facturer au plein tarif faisait payer au
        // client des tokens qui ne nous coûtent presque rien.
        assertThat(existing.getBilledTokens()).isEqualTo(5_556L);
    }

    @Test
    void theProviderCostWinsOverTheTokensWhenItIsReported() {
        UsageCounter existing = counter(0, 0);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.of(existing));
        when(usageCounterRepository.save(any(UsageCounter.class))).thenAnswer(inv -> inv.getArgument(0));

        quotaService.recordUsage(alice, new TurnTokens(1_000L, 200L, 0L, 0L),
                new java.math.BigDecimal("0.90"), null, null);

        assertThat(existing.getInputTokens()).isEqualTo(1_000L);
        assertThat(existing.getOutputTokens()).isEqualTo(200L);
        assertThat(existing.getBilledTokens()).isEqualTo(100_000L);
    }

    @Test
    void aTurnWithoutTokensButWithACostIsStillCharged() {
        // Recherche web ou temps de bac à sable seuls : ne rien décompter serait faux.
        UsageCounter existing = counter(0, 0);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.of(existing));
        when(usageCounterRepository.save(any(UsageCounter.class))).thenAnswer(inv -> inv.getArgument(0));

        quotaService.recordUsage(alice, new TurnTokens(0L, 0L, 0L, 0L),
                new java.math.BigDecimal("0.18"), null, null);

        assertThat(existing.getBilledTokens()).isEqualTo(20_000L);
    }

    @Test
    void theQuotaIsOpposedOnTheBilledCountNotOnTheVolume() {
        stubQuota(1_000L);
        // Volume de 400 tokens traités, mais 1 000 facturés : c'est le décompte qui bloque.
        UsageCounter counter = UsageCounter.builder()
                .userId(alice).periodStart(expectedPeriod)
                .inputTokens(300L).outputTokens(100L).billedTokens(1_000L).build();
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.of(counter));

        assertThatThrownBy(() -> quotaService.assertWithinQuota(alice))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void theSnapshotSeparatesWhatIsBilledFromWhatWasProcessed() {
        stubQuota(1_000L);
        UsageCounter counter = UsageCounter.builder()
                .userId(alice).periodStart(expectedPeriod)
                .inputTokens(300L).outputTokens(100L).billedTokens(250L).build();
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.of(counter));

        UsageSnapshot snapshot = quotaService.currentUsage(alice);

        // Les deux chiffres sont justes, et ce ne sont pas les mêmes : l'écran doit dire lequel il
        // montre (SF-63-03).
        assertThat(snapshot.usedTokens()).isEqualTo(250L);
        assertThat(snapshot.processedTokens()).isEqualTo(400L);
        assertThat(snapshot.remainingTokens()).isEqualTo(750L);
    }

    @Test
    void recordUsageIgnoresZeroConsumption() {
        quotaService.recordUsage(alice, 0, 0);
        verify(usageCounterRepository, never()).save(any());
    }

    @Test
    void recordUsageAsksTheAlertServiceToJudgeTheThreshold() {
        UsageCounter existing = counter(100, 50);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.of(existing));
        when(usageCounterRepository.save(any(UsageCounter.class))).thenAnswer(inv -> inv.getArgument(0));

        quotaService.recordUsage(alice, 10, 5);

        // Le seuil est jugé sur le compteur DÉJÀ incrémenté (F-42) : 110 + 55.
        verify(quotaAlertService).evaluateAfterUsage(alice, existing);
        assertThat(existing.totalTokens()).isEqualTo(165);
    }

    /**
     * F-42 — l'alerte informe, elle ne bloque jamais. Une évaluation de seuil qui échoue ne doit ni
     * faire remonter d'exception à l'appelant, ni faire perdre la consommation : une alerte manquée
     * est un défaut d'information, une consommation perdue serait un défaut de facturation.
     */
    @Test
    void recordUsageStillSavesConsumptionWhenTheAlertEvaluationFails() {
        UsageCounter existing = counter(100, 50);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.of(existing));
        when(usageCounterRepository.save(any(UsageCounter.class))).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doThrow(new IllegalStateException("alerte indisponible"))
                .when(quotaAlertService).evaluateAfterUsage(any(), any());

        assertThatCode(() -> quotaService.recordUsage(alice, 10, 5)).doesNotThrowAnyException();

        assertThat(existing.getInputTokens()).isEqualTo(110);
        assertThat(existing.getOutputTokens()).isEqualTo(55);
        verify(usageCounterRepository).save(existing);
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

    // ---------------------------------------------------------------------------------------------
    // Bac à sable (SF-28-12) : cumul du temps de session et plafond de garde.
    // ---------------------------------------------------------------------------------------------

    private UsageCounter counterWithSandbox(long sandboxSeconds) {
        return UsageCounter.builder()
                .userId(alice).periodStart(expectedPeriod).sandboxSeconds(sandboxSeconds).build();
    }

    @Test
    void recordSandboxSecondsCreatesCounterOnFirstCall() {
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.empty());
        when(usageCounterRepository.save(any(UsageCounter.class))).thenAnswer(inv -> inv.getArgument(0));

        quotaService.recordSandboxSeconds(alice, 42L);

        ArgumentCaptor<UsageCounter> captor = ArgumentCaptor.forClass(UsageCounter.class);
        verify(usageCounterRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        UsageCounter last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.getUserId()).isEqualTo(alice);
        assertThat(last.getPeriodStart()).isEqualTo(expectedPeriod);
        assertThat(last.getSandboxSeconds()).isEqualTo(42L);
    }

    @Test
    void recordSandboxSecondsIncrementsExistingCounter() {
        UsageCounter existing = counterWithSandbox(30L);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.of(existing));
        when(usageCounterRepository.save(any(UsageCounter.class))).thenAnswer(inv -> inv.getArgument(0));

        quotaService.recordSandboxSeconds(alice, 12L);

        assertThat(existing.getSandboxSeconds()).isEqualTo(42L);
    }

    @Test
    void recordSandboxSecondsIgnoresNonPositive() {
        quotaService.recordSandboxSeconds(alice, 0L);
        quotaService.recordSandboxSeconds(alice, -5L);
        verify(usageCounterRepository, never()).save(any());
    }

    @Test
    void currentPeriodSandboxSecondsReadsValueAndZeroWhenAbsent() {
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.of(counterWithSandbox(77L)));
        assertThat(quotaService.currentPeriodSandboxSeconds(alice)).isEqualTo(77L);

        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.empty());
        assertThat(quotaService.currentPeriodSandboxSeconds(alice)).isZero();
    }

    @Test
    void assertWithinSandboxLimitPassesWhenBelowCap() {
        // Plafond = 100 s (config figée) ; cumul 99 s → OK.
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.of(counterWithSandbox(99L)));

        assertThatCode(() -> quotaService.assertWithinSandboxLimit(alice)).doesNotThrowAnyException();
    }

    @Test
    void assertWithinSandboxLimitThrowsWhenAtCap() {
        // Cumul 100 s == plafond → refus.
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.of(counterWithSandbox(100L)));

        assertThatThrownBy(() -> quotaService.assertWithinSandboxLimit(alice))
                .isInstanceOf(SandboxLimitExceededException.class);
    }

    // ------------------------------------------------ F-41 / SF-41-01 : l'offre BYOK n'a rien à épuiser

    /** Abonnement BYOK en cours : quota 0, mais servi par la clé du client (jamais un impayé). */
    private void stubByokPlan() {
        Subscription sub = Subscription.builder()
                .userId(alice).status(SubscriptionStatus.ACTIVE).planCode(PlanCode.BYOK).build();
        when(subscriptionService.getOrCreateForUser(alice)).thenReturn(sub);
        when(entitlementService.isCustomerKeyBilled(sub)).thenReturn(true);
    }

    @Test
    void assertWithinQuotaPassesForByokPlanEvenWithConsumptionRecorded() {
        // Le piège de la feature : quota 0 et consommation déjà enregistrée => `used >= quota` serait
        // vrai et bloquerait un client parfaitement à jour. La dérogation BYOK doit primer.
        stubByokPlan();
        when(byokKeyService.requireActiveApiKey(alice)).thenReturn("sk-ant-user-key");

        assertThatCode(() -> quotaService.assertWithinQuota(alice)).doesNotThrowAnyException();
    }

    @Test
    void assertWithinQuotaSkipsCounterLookupForByokPlan() {
        // Rien à compter : le compteur de période n'est même pas lu (la limite est chez le fournisseur).
        stubByokPlan();
        when(byokKeyService.requireActiveApiKey(alice)).thenReturn("sk-ant-user-key");

        quotaService.assertWithinQuota(alice);

        verify(usageCounterRepository, never()).findByUserIdAndPeriodStart(any(), any());
    }

    @Test
    void assertWithinQuotaStillThrowsForExpiredSubscriptionResolvingToZero() {
        // Non-régression du fail-closed : l'AUTRE zéro — celui de l'abonnement expiré — bloque toujours.
        Subscription canceled = Subscription.builder()
                .userId(alice).status(SubscriptionStatus.CANCELED).planCode(PlanCode.PRO).build();
        when(subscriptionService.getOrCreateForUser(alice)).thenReturn(canceled);
        when(entitlementService.isCustomerKeyBilled(canceled)).thenReturn(false);
        when(entitlementService.resolveMonthlyTokenQuota(canceled)).thenReturn(0L);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> quotaService.assertWithinQuota(alice))
                .isInstanceOf(QuotaExceededException.class);
    }

    // ------------------------------------------------ F-41 / SF-41-02 : l'offre BYOK exige la clé

    @Test
    void assertWithinQuotaDemandsTheKeyOnAByokPlan() {
        // Sans clé, l'appel repartirait sur la clé de la PLATEFORME (`.orElse(null)` chez tous les
        // appelants) : un client qui ne paie aucun jeton consommerait ceux de la gateway.
        stubByokPlan();
        when(byokKeyService.requireActiveApiKey(alice))
                .thenThrow(new ByokKeyRequiredException("Aucune clé enregistrée."));

        assertThatThrownBy(() -> quotaService.assertWithinQuota(alice))
                .isInstanceOf(ByokKeyRequiredException.class);
    }

    @Test
    void assertWithinQuotaNeverDemandsAKeyOnAHostedPlan() {
        // Non-régression Hosted : un abonné Solo sans clé est servi par la clé plateforme, comme avant.
        stubQuota(1_000L);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.of(counter(10, 10)));

        quotaService.assertWithinQuota(alice);

        verify(byokKeyService, never()).requireActiveApiKey(any());
    }

    @Test
    void expiredByokSubscriptionIsRefusedOnTheSubscriptionBeforeTheKey() {
        // Ordre des refus : l'abonnement d'abord. Parler de sa clé à un abonné résilié le ferait
        // travailler pour rien.
        Subscription canceledByok = Subscription.builder()
                .userId(alice).status(SubscriptionStatus.CANCELED).planCode(PlanCode.BYOK).build();
        when(subscriptionService.getOrCreateForUser(alice)).thenReturn(canceledByok);
        when(entitlementService.isCustomerKeyBilled(canceledByok)).thenReturn(false);
        when(entitlementService.resolveMonthlyTokenQuota(canceledByok)).thenReturn(0L);
        when(usageCounterRepository.findByUserIdAndPeriodStart(alice, expectedPeriod))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> quotaService.assertWithinQuota(alice))
                .isInstanceOf(QuotaExceededException.class);
        verify(byokKeyService, never()).requireActiveApiKey(any());
    }
}
