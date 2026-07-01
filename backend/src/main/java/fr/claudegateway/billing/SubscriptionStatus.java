package fr.claudegateway.billing;

/**
 * Cycle de vie d'un abonnement (F-09). Aligné sur les statuts d'abonnement Stripe pertinents pour
 * la V1 ; peuplé par le webhook en SF-09-02, initialisé à {@link #TRIALING} lors du provisionnement
 * de l'essai gratuit.
 */
public enum SubscriptionStatus {
    /** Essai gratuit en cours (aucun plan payant encore). */
    TRIALING,
    /** Abonnement payant actif. */
    ACTIVE,
    /** Paiement en échec, accès en sursis. */
    PAST_DUE,
    /** Abonnement annulé (fin de période atteinte ou annulation explicite). */
    CANCELED,
    /** Souscription initiée mais paiement non finalisé. */
    INCOMPLETE
}
