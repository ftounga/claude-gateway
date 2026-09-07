package fr.claudegateway.billing.provider;

import java.util.UUID;

import fr.claudegateway.billing.BillingPeriod;
import fr.claudegateway.billing.Plan;

/**
 * Commande de création d'une session de paiement, passée au {@link BillingProvider}. Regroupe tout
 * ce dont le fournisseur a besoin sans exposer d'entité de persistance.
 *
 * @param userId             utilisateur acheteur (contexte de sécurité)
 * @param customerEmail      email de l'utilisateur (pré-rempli côté Checkout)
 * @param existingCustomerId identifiant client fournisseur déjà connu, ou {@code null} (nouveau client)
 * @param plan               plan choisi
 * @param priceId            price ID fournisseur résolu depuis la configuration
 * @param period             périodicité <b>achetée</b> (F-43) : elle détermine le mode de paiement
 *                           (unique pour un pass journée, abonnement sinon) et voyage en métadonnée
 *                           jusqu'au webhook, exactement comme le code de plan
 */
public record CheckoutCommand(
        UUID userId,
        String customerEmail,
        String existingCustomerId,
        Plan plan,
        String priceId,
        BillingPeriod period) {

    /**
     * Commande sans périodicité explicite : celle du plan (F-09, avant F-43). Conservée pour que
     * les appelants qui n'achètent pas d'engagement particulier restent inchangés.
     */
    public CheckoutCommand(
            UUID userId, String customerEmail, String existingCustomerId, Plan plan, String priceId) {
        this(userId, customerEmail, existingCustomerId, plan, priceId, plan.period());
    }
}
