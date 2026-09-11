package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fr.claudegateway.billing.PlanCatalog;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.billing.seat.SeatQuotaService;

/**
 * Tests unitaires de la résolution d'entitlement (SF-10-01) : traduction de l'état d'abonnement
 * (F-09) en allocation mensuelle de tokens, fail-closed pour tout état n'ouvrant pas d'accès.
 *
 * <p>Depuis F-41 / SF-41-01, ils figent aussi la distinction qui fait la feature : une offre BYOK
 * en cours alloue <b>0 jeton</b> comme un abonnement expiré, mais elle n'est <b>pas</b> un impayé.
 * Confondre les deux bloquerait un client payant dès son premier appel.</p>
 */
class EntitlementServiceTest {

    private EntitlementService service;

    /**
     * Aucun poste supplémentaire (F-65) : ces tests décrivent l'allocation du PLAN, et le supplément
     * par poste est une autre histoire — celle de {@code SeatQuotaServiceTest}.
     */
    private final SeatQuotaService seatQuotaService = mock(SeatQuotaService.class);

    @BeforeEach
    void setUp() {
        QuotaProperties properties = new QuotaProperties(
                200_000L,
                Map.of("SOLO", 1_000_000L, "PRO", 5_000_000L, "DAILY", 500_000L, "GOLD", 12_000_000L,
                        "BYOK", 0L),
                null);
        service = new EntitlementService(properties, new PlanCatalog(), seatQuotaService);
    }

    private Subscription subscription(SubscriptionStatus status, PlanCode plan, OffsetDateTime trialEndsAt) {
        return Subscription.builder()
                .userId(UUID.randomUUID())
                .status(status)
                .planCode(plan)
                .trialEndsAt(trialEndsAt)
                .build();
    }

    @Test
    void activePlanResolvesToPlanQuota() {
        assertThat(service.resolveMonthlyTokenQuota(
                subscription(SubscriptionStatus.ACTIVE, PlanCode.PRO, null)))
                .isEqualTo(5_000_000L);
    }

    @Test
    void pastDueKeepsPlanQuotaAsGrace() {
        assertThat(service.resolveMonthlyTokenQuota(
                subscription(SubscriptionStatus.PAST_DUE, PlanCode.SOLO, null)))
                .isEqualTo(1_000_000L);
    }

    @Test
    void activeTrialResolvesToTrialQuota() {
        assertThat(service.resolveMonthlyTokenQuota(
                subscription(SubscriptionStatus.TRIALING, null, OffsetDateTime.now().plusDays(5))))
                .isEqualTo(200_000L);
    }

    @Test
    void expiredTrialResolvesToZero() {
        assertThat(service.resolveMonthlyTokenQuota(
                subscription(SubscriptionStatus.TRIALING, null, OffsetDateTime.now().minusDays(1))))
                .isZero();
    }

    @Test
    void goldActiveResolvesToGoldQuota() {
        // SF-28-06 : l'offre Gold active débloque son quota (12 M) sans logique dédiée (générique).
        assertThat(service.resolveMonthlyTokenQuota(
                subscription(SubscriptionStatus.ACTIVE, PlanCode.GOLD, null)))
                .isEqualTo(12_000_000L);
    }

    @Test
    void goldCanceledResolvesToZero() {
        // SF-28-06 : Gold annulé => aucun quota (fail-closed).
        assertThat(service.resolveMonthlyTokenQuota(
                subscription(SubscriptionStatus.CANCELED, PlanCode.GOLD, null)))
                .isZero();
    }

    @Test
    void canceledResolvesToZero() {
        assertThat(service.resolveMonthlyTokenQuota(
                subscription(SubscriptionStatus.CANCELED, PlanCode.PRO, null)))
                .isZero();
    }

    @Test
    void incompleteResolvesToZero() {
        assertThat(service.resolveMonthlyTokenQuota(
                subscription(SubscriptionStatus.INCOMPLETE, null, null)))
                .isZero();
    }

    @Test
    void activeWithUnconfiguredPlanFailsClosed() {
        EntitlementService noPlans = new EntitlementService(
                new QuotaProperties(200_000L, Map.of(), null), new PlanCatalog(), seatQuotaService);
        assertThat(noPlans.resolveMonthlyTokenQuota(
                subscription(SubscriptionStatus.ACTIVE, PlanCode.PRO, null)))
                .isZero();
    }

    // ------------------------------------------------ F-41 / SF-41-01 : le zéro qui n'est pas un impayé

    @Test
    void byokActiveResolvesToZeroTokens() {
        // Le contrat de l'offre : la plateforme n'alloue aucun jeton, le client apporte les siens.
        assertThat(service.resolveMonthlyTokenQuota(
                subscription(SubscriptionStatus.ACTIVE, PlanCode.BYOK, null)))
                .isZero();
    }

    @Test
    void byokActiveIsCustomerKeyBilled() {
        // ... et ce zéro-là se distingue explicitement, sans quoi il vaudrait un abonnement expiré.
        assertThat(service.isCustomerKeyBilled(
                subscription(SubscriptionStatus.ACTIVE, PlanCode.BYOK, null)))
                .isTrue();
    }

    @Test
    void byokPastDueStaysCustomerKeyBilled() {
        // Sursis de paiement : même politique que pour les autres plans, l'accès reste ouvert.
        assertThat(service.isCustomerKeyBilled(
                subscription(SubscriptionStatus.PAST_DUE, PlanCode.BYOK, null)))
                .isTrue();
    }

    @Test
    void byokCanceledIsNotCustomerKeyBilled() {
        // Le cœur du fail-closed : une offre BYOK résiliée redevient un zéro qui bloque.
        Subscription canceled = subscription(SubscriptionStatus.CANCELED, PlanCode.BYOK, null);
        assertThat(service.resolveMonthlyTokenQuota(canceled)).isZero();
        assertThat(service.isCustomerKeyBilled(canceled)).isFalse();
    }

    @Test
    void byokIncompleteIsNotCustomerKeyBilled() {
        assertThat(service.isCustomerKeyBilled(
                subscription(SubscriptionStatus.INCOMPLETE, PlanCode.BYOK, null)))
                .isFalse();
    }

    @Test
    void hostedPlansAreNeverCustomerKeyBilled() {
        // Non-régression : aucun plan Hosted ne doit hériter de la dérogation BYOK.
        assertThat(service.isCustomerKeyBilled(subscription(SubscriptionStatus.ACTIVE, PlanCode.SOLO, null)))
                .isFalse();
        assertThat(service.isCustomerKeyBilled(subscription(SubscriptionStatus.ACTIVE, PlanCode.PRO, null)))
                .isFalse();
        assertThat(service.isCustomerKeyBilled(subscription(SubscriptionStatus.ACTIVE, PlanCode.DAILY, null)))
                .isFalse();
        assertThat(service.isCustomerKeyBilled(subscription(SubscriptionStatus.ACTIVE, PlanCode.GOLD, null)))
                .isFalse();
    }

    @Test
    void activeTrialIsNotCustomerKeyBilled() {
        // L'essai n'a pas de plan : il consomme les jetons de la plateforme, pas ceux d'une clé.
        assertThat(service.isCustomerKeyBilled(
                subscription(SubscriptionStatus.TRIALING, null, OffsetDateTime.now().plusDays(5))))
                .isFalse();
    }
}
