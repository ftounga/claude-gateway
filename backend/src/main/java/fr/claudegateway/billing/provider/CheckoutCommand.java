package fr.claudegateway.billing.provider;

import java.util.UUID;

import fr.claudegateway.billing.Plan;

/**
 * Commande de création d'une session de paiement, passée au {@link BillingProvider}. Regroupe tout
 * ce dont le fournisseur a besoin sans exposer d'entité de persistance.
 *
 * @param userId             utilisateur acheteur (contexte de sécurité)
 * @param customerEmail      email de l'utilisateur (pré-rempli côté Checkout)
 * @param existingCustomerId identifiant client fournisseur déjà connu, ou {@code null} (nouveau client)
 * @param plan               plan choisi (détermine le mode : abonnement ou paiement unique)
 * @param priceId            price ID fournisseur résolu depuis la configuration
 */
public record CheckoutCommand(
        UUID userId,
        String customerEmail,
        String existingCustomerId,
        Plan plan,
        String priceId) {
}
