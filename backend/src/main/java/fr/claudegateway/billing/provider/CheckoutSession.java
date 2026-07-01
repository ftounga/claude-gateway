package fr.claudegateway.billing.provider;

/**
 * Résultat neutre de la création d'une session de paiement.
 *
 * @param url       URL de redirection vers le formulaire de paiement hébergé
 * @param sessionId identifiant de session fournisseur (interne, jamais exposé au client)
 */
public record CheckoutSession(String url, String sessionId) {
}
