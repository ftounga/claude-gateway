package fr.claudegateway.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Requête de changement de plan d'un abonnement existant (upgrade/downgrade, SF-21-05 ; changement
 * de périodicité, F-43). Le {@code planCode} est validé contre le catalogue côté service (400 si
 * inconnu ; 409 si aucun abonnement actif).
 *
 * @param planCode code du plan cible (ex. {@code PRO})
 * @param period   périodicité cible : {@code MONTHLY} ou {@code YEARLY} (F-43). <b>Optionnelle</b> —
 *                 absente, elle vaut {@code MONTHLY}.
 */
public record ChangePlanRequest(
        @NotBlank String planCode,
        @Size(max = 16) String period) {
}
