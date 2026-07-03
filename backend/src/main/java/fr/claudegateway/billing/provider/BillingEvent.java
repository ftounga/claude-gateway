package fr.claudegateway.billing.provider;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.billing.PlanCode;

/**
 * Événement de facturation normalisé (indépendant du fournisseur), produit par
 * {@link BillingProvider#parseWebhookEvent}. Ne porte que les données nécessaires à la mise à jour
 * d'un abonnement ; aucun secret ni payload brut.
 *
 * @param type                 type normalisé
 * @param userId               utilisateur concerné (extrait des métadonnées), ou {@code null} si absent
 * @param stripeCustomerId     identifiant client fournisseur, ou {@code null}
 * @param stripeSubscriptionId identifiant abonnement fournisseur, ou {@code null} (pass unique)
 * @param planCode             plan concerné, ou {@code null} si non porté par l'événement
 * @param status               statut fournisseur brut (ex. {@code active}, {@code canceled}), ou {@code null}
 * @param currentPeriodEnd     fin de période de facturation, ou {@code null}
 * @param eventId              identifiant de l'événement fournisseur (idempotence), ou {@code null}
 * @param topupCode            code du pack de tokens racheté (pour {@code TOPUP_COMPLETED}), ou {@code null}
 */
public record BillingEvent(
        BillingEventType type,
        UUID userId,
        String stripeCustomerId,
        String stripeSubscriptionId,
        PlanCode planCode,
        String status,
        OffsetDateTime currentPeriodEnd,
        String eventId,
        String topupCode) {

    /** Fabrique un événement non géré (ignoré par le service). */
    public static BillingEvent unhandled() {
        return new BillingEvent(BillingEventType.UNHANDLED, null, null, null, null, null, null, null, null);
    }
}
