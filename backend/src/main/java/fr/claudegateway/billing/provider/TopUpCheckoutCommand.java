package fr.claudegateway.billing.provider;

import java.util.UUID;

/**
 * Commande de création d'une session de paiement <b>one-shot</b> pour un rachat de tokens (top-up,
 * F-21 / SF-21-02), passée au {@link BillingProvider}. Contrairement à {@link CheckoutCommand}
 * (abonnement), le mode est toujours un paiement unique ; le fournisseur porte l'intention via des
 * métadonnées ({@code kind=topup}, {@code topupCode}) pour que le webhook puisse créditer le bon pack.
 *
 * @param userId             utilisateur acheteur (contexte de sécurité)
 * @param customerEmail      email de l'utilisateur (pré-rempli côté Checkout), ou {@code null}
 * @param existingCustomerId identifiant client fournisseur déjà connu, ou {@code null} (nouveau client)
 * @param packCode           code du pack de tokens acheté (résolu depuis le catalogue serveur)
 * @param priceId            price ID fournisseur résolu depuis la configuration
 */
public record TopUpCheckoutCommand(
        UUID userId,
        String customerEmail,
        String existingCustomerId,
        String packCode,
        String priceId) {
}
