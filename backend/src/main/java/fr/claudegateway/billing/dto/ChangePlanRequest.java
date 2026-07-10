package fr.claudegateway.billing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Requête de changement de plan d'un abonnement existant (upgrade/downgrade, SF-21-05). Le
 * {@code planCode} est validé contre le catalogue côté service (400 si inconnu ; 409 si aucun
 * abonnement actif).
 *
 * @param planCode code du plan cible (ex. {@code PRO})
 */
public record ChangePlanRequest(@NotBlank String planCode) {
}
