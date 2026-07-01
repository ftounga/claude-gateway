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
    INCOMPLETE;

    /**
     * Traduit un statut d'abonnement brut Stripe vers le statut interne. Tout statut inconnu est
     * traité prudemment comme {@link #INCOMPLETE} (accès non ouvert par défaut).
     *
     * @param stripeStatus statut Stripe (ex. {@code active}, {@code past_due}), insensible à la casse
     * @return statut interne correspondant
     */
    public static SubscriptionStatus fromStripe(String stripeStatus) {
        if (stripeStatus == null) {
            return INCOMPLETE;
        }
        return switch (stripeStatus.toLowerCase()) {
            case "active" -> ACTIVE;
            case "trialing" -> TRIALING;
            case "past_due" -> PAST_DUE;
            case "canceled", "unpaid" -> CANCELED;
            default -> INCOMPLETE;
        };
    }
}
