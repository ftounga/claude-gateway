package fr.claudegateway.billing.provider;

/**
 * Commande de changement de plan d'un abonnement existant (upgrade/downgrade, F-21 / SF-21-05),
 * passée au {@link BillingProvider}. Ne référence que des identifiants fournisseur, jamais d'entité
 * de persistance.
 *
 * @param stripeSubscriptionId identifiant de l'abonnement fournisseur à mettre à jour
 * @param newPriceId           price ID fournisseur du plan cible (résolu depuis la configuration)
 */
public record ChangePlanCommand(String stripeSubscriptionId, String newPriceId) {
}
