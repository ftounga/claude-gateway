package fr.claudegateway.billing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Requête de création d'une session de paiement (F-09). Le {@code planCode} est validé contre le
 * catalogue côté service (400 si inconnu).
 *
 * @param planCode code du plan à souscrire (ex. {@code PRO})
 */
public record CheckoutRequest(@NotBlank String planCode) {
}
