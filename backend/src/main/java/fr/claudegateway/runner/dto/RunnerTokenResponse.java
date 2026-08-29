package fr.claudegateway.runner.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.runner.RunnerToken;

/**
 * Vue métadonnées d'un jeton runner (F-38 / SF-38-01) : jamais la valeur du jeton, seulement de quoi
 * l'identifier et connaître son état.
 */
public record RunnerTokenResponse(
        UUID id,
        String label,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime lastSeenAt,
        boolean revoked) {

    public static RunnerTokenResponse from(RunnerToken token) {
        return new RunnerTokenResponse(
                token.getId(),
                token.getLabel(),
                token.getCreatedAt(),
                token.getExpiresAt(),
                token.getLastSeenAt(),
                token.getRevokedAt() != null);
    }
}
