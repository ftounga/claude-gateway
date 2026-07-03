package fr.claudegateway.billing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Requête de création d'une session de rachat de tokens (F-21 / SF-21-02). Le {@code packCode} est
 * validé contre le catalogue côté service (400 si inconnu). Le montant de tokens n'est jamais fourni
 * par le client : il est autoritatif côté serveur (catalogue).
 *
 * @param packCode code du pack de tokens à racheter (ex. {@code STANDARD})
 */
public record TopUpCheckoutRequest(@NotBlank String packCode) {
}
