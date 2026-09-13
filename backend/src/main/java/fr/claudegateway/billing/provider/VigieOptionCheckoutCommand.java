package fr.claudegateway.billing.provider;

import java.util.UUID;

/**
 * Commande de création d'une session de paiement pour l'<b>option Vigie</b> (F-107 / SF-107-03), passée
 * au {@link BillingProvider}. Comme l'option Forge, c'est un <b>abonnement mensuel distinct</b> de celui
 * du plan : la session créée donnera un identifiant d'abonnement à part, rangé dans sa propre colonne.
 *
 * @param userId             utilisateur acheteur (contexte de sécurité)
 * @param customerEmail      email de l'utilisateur (pré-rempli côté Checkout)
 * @param existingCustomerId identifiant client fournisseur déjà connu, ou {@code null}
 * @param priceId            price ID fournisseur de l'option, résolu depuis la configuration
 */
public record VigieOptionCheckoutCommand(
        UUID userId,
        String customerEmail,
        String existingCustomerId,
        String priceId) {
}
