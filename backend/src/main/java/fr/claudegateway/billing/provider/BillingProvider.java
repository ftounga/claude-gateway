package fr.claudegateway.billing.provider;

/**
 * Abstraction du fournisseur de paiement (F-09). Le code métier (checkout, webhook) dépend
 * <b>uniquement</b> de cette interface, jamais de Stripe en direct — parallèle de {@code AIProvider}
 * côté IA (PROJECT.md §14.6, Provider Independence). Permet de tester sans réseau et de préparer un
 * futur fournisseur de paiement.
 */
public interface BillingProvider {

    /** Vrai si le fournisseur est configuré (secret présent) et donc réellement appelable. */
    boolean isConfigured();

    /**
     * Crée une session de paiement hébergée pour la commande donnée.
     *
     * @throws BillingProviderUnavailableException si le fournisseur n'est pas configuré
     * @throws BillingProviderException            en cas d'échec d'appel au fournisseur
     */
    CheckoutSession createCheckoutSession(CheckoutCommand command);

    /**
     * Crée une session de paiement <b>one-shot</b> hébergée pour un rachat de tokens (top-up, F-21).
     * La session porte les métadonnées nécessaires ({@code kind=topup}, {@code topupCode}, {@code userId})
     * pour que le webhook de paiement finalisé puisse créditer le bon pack au bon utilisateur.
     *
     * @throws BillingProviderUnavailableException si le fournisseur ou le price ID n'est pas configuré
     * @throws BillingProviderException            en cas d'échec d'appel au fournisseur
     */
    CheckoutSession createTopUpCheckoutSession(TopUpCheckoutCommand command);

    /**
     * Change le plan d'un abonnement existant (upgrade/downgrade, F-21 / SF-21-05) : met à jour
     * l'item de l'abonnement vers le nouveau price, avec proratisation. Ne crée pas de nouvel
     * abonnement (à la différence de {@link #createCheckoutSession(CheckoutCommand)}).
     *
     * @throws BillingProviderUnavailableException si le fournisseur ou les identifiants ne sont pas configurés
     * @throws BillingProviderException            en cas d'échec d'appel au fournisseur
     */
    void changeSubscriptionPlan(ChangePlanCommand command);

    /**
     * Vérifie la signature d'un webhook et traduit l'événement brut en événement normalisé.
     *
     * @param payload         corps brut de la requête (signé)
     * @param signatureHeader en-tête de signature du fournisseur
     * @return événement normalisé (éventuellement {@link BillingEvent#unhandled()})
     * @throws WebhookVerificationException        si la signature est invalide
     * @throws BillingProviderUnavailableException si le secret de webhook n'est pas configuré
     */
    BillingEvent parseWebhookEvent(String payload, String signatureHeader);
}
