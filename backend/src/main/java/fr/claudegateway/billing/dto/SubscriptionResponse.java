package fr.claudegateway.billing.dto;

import java.time.OffsetDateTime;

import fr.claudegateway.billing.Subscription;

/**
 * Représentation de l'abonnement de l'utilisateur courant exposée au client (F-09).
 *
 * <p>Volontairement <b>expurgée</b> des identifiants Stripe ({@code stripe_customer_id},
 * {@code stripe_subscription_id}) : ceux-ci sont internes et ne doivent jamais transiter vers le
 * navigateur (PROJECT.md §11.14).</p>
 *
 * @param status           statut courant de l'abonnement
 * @param planCode         code du plan payant, ou {@code null} en essai
 * @param trialEndsAt      fin de l'essai gratuit, ou {@code null}
 * @param currentPeriodEnd fin de la période de facturation courante, ou {@code null}
 */
public record SubscriptionResponse(
        String status,
        String planCode,
        OffsetDateTime trialEndsAt,
        OffsetDateTime currentPeriodEnd) {

    public static SubscriptionResponse from(Subscription subscription) {
        return new SubscriptionResponse(
                subscription.getStatus().name(),
                subscription.getPlanCode() != null ? subscription.getPlanCode().name() : null,
                subscription.getTrialEndsAt(),
                subscription.getCurrentPeriodEnd());
    }
}
