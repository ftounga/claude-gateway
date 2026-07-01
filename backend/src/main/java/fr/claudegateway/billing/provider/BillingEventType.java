package fr.claudegateway.billing.provider;

/**
 * Type d'événement de facturation <b>normalisé</b> (indépendant du fournisseur). Le
 * {@link BillingProvider} traduit les événements bruts du fournisseur (Stripe) vers cet ensemble
 * restreint, ce qui garde le code métier ({@code WebhookService}) découplé de Stripe.
 */
public enum BillingEventType {
    /** Paiement/souscription finalisé après un Checkout. */
    CHECKOUT_COMPLETED,
    /** Cycle de vie d'un abonnement mis à jour (statut, période, plan). */
    SUBSCRIPTION_UPDATED,
    /** Abonnement supprimé/annulé côté fournisseur. */
    SUBSCRIPTION_DELETED,
    /** Événement reçu mais non pertinent pour la V1 : ignoré. */
    UNHANDLED
}
