package fr.claudegateway.runner.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Réponse à l'appairage (F-38 / SF-38-01). Le jeton en clair n'apparaît qu'ici et n'est jamais
 * réexposé par l'API.
 */
public record PairResponse(String token, UUID workspaceId, OffsetDateTime expiresAt) {
}
