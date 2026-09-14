package fr.claudegateway.mcp;

import java.util.Set;
import java.util.UUID;

/**
 * Détails de l'authentification MCP, posés comme {@code details} de l'{@code Authentication} par le
 * serveur de ressources OAuth (jeton d'accès) ou par le filtre des jetons personnels. Le
 * {@link McpTransportContextFactory} les relit pour construire le {@link McpCallContext} lu par les
 * outils et le journal.
 *
 * @param authKind    origine de l'authentification (OAuth ou jeton personnel)
 * @param tokenId     identifiant du jeton personnel (null pour OAuth)
 * @param clientLabel libellé lisible du client, pour le journal
 * @param scopes      périmètres accordés
 * @param hostIds     postes accessibles (jeton personnel ; vide pour OAuth en fondation)
 */
public record McpAuthDetails(
        McpAuthKind authKind,
        UUID tokenId,
        String clientLabel,
        Set<String> scopes,
        Set<UUID> hostIds) {
}
