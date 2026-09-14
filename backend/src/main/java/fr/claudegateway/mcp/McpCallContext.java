package fr.claudegateway.mcp;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;

import fr.claudegateway.auth.AuthenticatedUser;

/**
 * Contexte d'un appel d'outil MCP : l'identité authentifiée du porteur présenté et ce qu'il ouvre
 * (origine de l'authentification, jeton personnel éventuel, périmètres, postes accessibles), capturé
 * sur le thread servlet au moment de la requête HTTP.
 *
 * <p>C'est le <b>point d'entrée unique</b> de l'isolation multi-tenant côté MCP (cadrage F-112 §6.2) :
 * un outil ne lit jamais le {@code user_id}, les périmètres ou les postes d'un paramètre d'entrée, il
 * les prend ici, résolus depuis l'authentification. Le journal MCP (SF-112-03) s'en sert aussi.</p>
 *
 * @param user        identité authentifiée
 * @param authKind    origine (OAuth ou jeton personnel)
 * @param tokenId     jeton personnel utilisé (null pour OAuth)
 * @param clientLabel libellé lisible du client, pour le journal
 * @param scopes      périmètres accordés
 * @param hostIds     postes accessibles (jeton personnel ; vide pour OAuth en fondation)
 */
public record McpCallContext(
        AuthenticatedUser user,
        McpAuthKind authKind,
        UUID tokenId,
        String clientLabel,
        Set<String> scopes,
        Set<UUID> hostIds) {

    /** Clé sous laquelle le contexte est déposé dans le {@link McpTransportContext}. */
    public static final String KEY = "fr.claudegateway.mcp.callContext";

    /**
     * Récupère le contexte d'appel depuis l'échange MCP.
     *
     * @throws IllegalStateException si aucun contexte n'a été capturé (requête non authentifiée qui
     *         aurait dû être refusée par la chaîne de sécurité en amont)
     */
    public static McpCallContext require(McpSyncServerExchange exchange) {
        return from(exchange).orElseThrow(() -> new IllegalStateException(
                "Aucun contexte d'appel MCP : la requête aurait dû être authentifiée par la chaîne /mcp"));
    }

    /** Récupère le contexte d'appel depuis l'échange MCP, ou vide s'il est absent. */
    public static Optional<McpCallContext> from(McpSyncServerExchange exchange) {
        if (exchange == null) {
            return Optional.empty();
        }
        Object value = exchange.transportContext().get(KEY);
        return value instanceof McpCallContext context ? Optional.of(context) : Optional.empty();
    }

    /** Vrai si le périmètre demandé a été accordé au porteur. */
    public boolean hasScope(String scope) {
        return scopes != null && scopes.contains(scope);
    }

    /** Vrai si le poste est accessible par ce porteur (accès poste par poste, cadrage §4). */
    public boolean canAccessHost(UUID hostId) {
        return hostIds != null && hostIds.contains(hostId);
    }
}
