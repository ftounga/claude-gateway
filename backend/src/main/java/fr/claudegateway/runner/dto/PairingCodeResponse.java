package fr.claudegateway.runner.dto;

import java.time.OffsetDateTime;

/**
 * Réponse à la génération d'un code d'appairage (F-38 / SF-38-01). Le code n'apparaît qu'ici.
 */
public record PairingCodeResponse(String code, OffsetDateTime expiresAt) {
}
