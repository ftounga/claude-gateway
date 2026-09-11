package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.billing.PlanCatalog;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.billing.seat.SeatQuotaService;

/**
 * Le quota <b>effectif</b> : l'allocation du plan, plus la part apportée par les postes
 * supplémentaires (F-65 / SF-65-01).
 *
 * <p>Deux garanties, et la seconde compte autant que la première : que l'apport s'ajoute quand
 * l'abonnement est en cours, et qu'il <b>ne s'ajoute pas</b> ailleurs — un essai ne paie pas de
 * poste supplémentaire, et une offre BYOK n'a pas de jetons de plateforme à recevoir.</p>
 */
class EntitlementServiceSeatTest {

    private static final long SOLO_TOKENS = 1_000_000L;
    private static final long SEAT_TOKENS = 350_000L;

    private final SeatQuotaService seatQuotaService = mock(SeatQuotaService.class);
    private final EntitlementService service = new EntitlementService(
            new QuotaProperties(200_000L,
                    Map.of("SOLO", SOLO_TOKENS, "GOLD", 12_000_000L, "BYOK", 0L), null),
            new PlanCatalog(),
            seatQuotaService);

    @Test
    void anActiveSubscriptionReceivesTheShareOfItsExtraSeats() {
        Subscription subscription = subscription(SubscriptionStatus.ACTIVE, PlanCode.SOLO);
        when(seatQuotaService.grantedTokens(subscription.getUserId())).thenReturn(SEAT_TOKENS);

        assertThat(service.resolveEffectiveMonthlyTokenQuota(subscription))
                .isEqualTo(SOLO_TOKENS + SEAT_TOKENS);
        // L'allocation du PLAN, elle, ne bouge pas : c'est ce que le catalogue annonce.
        assertThat(service.resolveMonthlyTokenQuota(subscription)).isEqualTo(SOLO_TOKENS);
    }

    @Test
    void aSubscriptionInArrearsKeepsItsSeatsWhileItIsInReprieve() {
        Subscription subscription = subscription(SubscriptionStatus.PAST_DUE, PlanCode.SOLO);
        when(seatQuotaService.grantedTokens(subscription.getUserId())).thenReturn(SEAT_TOKENS);

        assertThat(service.resolveEffectiveMonthlyTokenQuota(subscription))
                .isEqualTo(SOLO_TOKENS + SEAT_TOKENS);
    }

    @Test
    void aTrialNeverReceivesASeatShare() {
        Subscription trial = Subscription.builder()
                .userId(UUID.randomUUID())
                .status(SubscriptionStatus.TRIALING)
                .trialEndsAt(OffsetDateTime.now().plusDays(3))
                .build();

        assertThat(service.resolveEffectiveMonthlyTokenQuota(trial)).isEqualTo(200_000L);
        verify(seatQuotaService, never()).grantedTokens(any());
    }

    @Test
    void aByokOfferKeepsItsZeroWhateverItsSeatCount() {
        Subscription byok = subscription(SubscriptionStatus.ACTIVE, PlanCode.BYOK);

        assertThat(service.resolveEffectiveMonthlyTokenQuota(byok)).isZero();
        verify(seatQuotaService, never()).grantedTokens(any());
    }

    @Test
    void aCancelledSubscriptionReceivesNothing() {
        Subscription cancelled = subscription(SubscriptionStatus.CANCELED, PlanCode.SOLO);

        assertThat(service.resolveEffectiveMonthlyTokenQuota(cancelled)).isZero();
        verify(seatQuotaService, never()).grantedTokens(any());
    }

    private static Subscription subscription(SubscriptionStatus status, PlanCode plan) {
        return Subscription.builder()
                .userId(UUID.randomUUID())
                .status(status)
                .planCode(plan)
                .build();
    }
}
