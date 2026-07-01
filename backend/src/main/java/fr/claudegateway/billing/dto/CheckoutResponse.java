package fr.claudegateway.billing.dto;

import fr.claudegateway.billing.provider.CheckoutSession;

/**
 * Réponse de création d'une session de paiement : uniquement l'URL de redirection vers le
 * formulaire de paiement hébergé. L'identifiant de session fournisseur reste interne.
 *
 * @param checkoutUrl URL de redirection vers le paiement
 */
public record CheckoutResponse(String checkoutUrl) {

    public static CheckoutResponse from(CheckoutSession session) {
        return new CheckoutResponse(session.url());
    }
}
