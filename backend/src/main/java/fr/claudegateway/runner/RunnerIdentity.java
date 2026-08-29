package fr.claudegateway.runner;

import java.util.UUID;

/**
 * Identité d'un runner authentifié par jeton (F-38). Second type de porteur d'identité de la
 * plateforme, distinct de l'{@code AuthenticatedUser} (JWT utilisateur) : il n'est jamais posé dans
 * le {@code SecurityContext} de la chaîne principale et ne transite que par la chaîne dédiée
 * {@code /runner/**}. Consommé par le canal WebSocket (SF-38-02).
 */
public record RunnerIdentity(UUID tokenId, UUID userId, UUID workspaceId) {
}
