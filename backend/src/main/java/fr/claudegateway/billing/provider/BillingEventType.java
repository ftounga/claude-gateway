package fr.claudegateway.billing.provider;

/**
 * Type d'événement de facturation <b>normalisé</b> (indépendant du fournisseur). Le
 * {@link BillingProvider} traduit les événements bruts du fournisseur (Stripe) vers cet ensemble
 * restreint, ce qui garde le code métier ({@code WebhookService}) découplé de Stripe.
 */
public enum BillingEventType {
    /** Paiement/souscription finalisé après un Checkout. */
    CHECKOUT_COMPLETED,
    /** Rachat de tokens (top-up, F-21) finalisé après un Checkout one-shot : crédite le quota. */
    TOPUP_COMPLETED,
    /**
     * Souscription de l'<b>option Atelier</b> (F-40) finalisée après un Checkout : ouvre le droit
     * d'Atelier. Distinct de {@link #CHECKOUT_COMPLETED} parce que l'événement porte l'identifiant
     * d'un <b>second</b> abonnement ; le confondre avec celui du plan écraserait le plan.
     */
    ATELIER_OPTION_COMPLETED,
    /** Cycle de vie de l'abonnement d'<b>option Atelier</b> mis à jour (statut). */
    ATELIER_OPTION_UPDATED,
    /** Abonnement d'<b>option Atelier</b> supprimé/résilié côté fournisseur : le droit se referme. */
    ATELIER_OPTION_DELETED,
    /** Cycle de vie d'un abonnement mis à jour (statut, période, plan). */
    SUBSCRIPTION_UPDATED,
    /** Abonnement supprimé/annulé côté fournisseur. */
    SUBSCRIPTION_DELETED,
    /** Événement reçu mais non pertinent pour la V1 : ignoré. */
    UNHANDLED
}
