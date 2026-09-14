package fr.claudegateway.mcp.token.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * DTO des jetons personnels et du journal MCP (F-112 / SF-112-03). Le secret en clair n'apparaît que
 * dans {@link CreatedTokenResponse}, renvoyé une seule fois à la création.
 */
public final class McpTokenDtos {

    private McpTokenDtos() {
    }

    /** Demande de création d'un jeton personnel. */
    public record CreateTokenRequest(
            String name,
            Set<String> scopes,
            Set<UUID> hostIds,
            Integer expiresInDays) {
    }

    /** Jeton renvoyé à la création : métadonnées + secret en clair (affiché une seule fois). */
    public record CreatedTokenResponse(String secret, TokenResponse token) {
    }

    /** Jeton listé : jamais le secret, seulement son préfixe affichable. */
    public record TokenResponse(
            UUID id,
            String name,
            String prefix,
            List<String> scopes,
            List<UUID> hostIds,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt,
            OffsetDateTime lastUsedAt,
            boolean revoked,
            boolean expired) {
    }

    /** Une ligne du journal MCP (sans contenu). */
    public record JournalEntryResponse(
            UUID id,
            String client,
            String authKind,
            String tool,
            UUID hostId,
            String paramsSummary,
            String result,
            Long durationMs,
            OffsetDateTime createdAt) {
    }

    /** Un poste proposé au sélecteur d'accès poste par poste. */
    public record HostOption(UUID id, String name) {
    }
}
