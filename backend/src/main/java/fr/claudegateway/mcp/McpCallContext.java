package fr.claudegateway.mcp;

import java.util.Optional;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;

import fr.claudegateway.auth.AuthenticatedUser;

/**
 * Contexte d'un appel d'outil MCP : l'identité authentifiée du jeton présenté, capturée sur le
 * thread servlet au moment de la requête HTTP (avant que le SDK ne bascule éventuellement de thread).
 *
 * <p>C'est le <b>point d'entrée unique</b> de l'isolation multi-tenant côté MCP : un outil ne lit
 * jamais le {@code user_id} d'un paramètre d'entrée, il le prend ici, résolu depuis
 * l'authentification (cadrage F-112 §6.2). En SF-112-01 le contexte ne porte que l'identité ;
 * SF-112-02/03 l'enrichissent (périmètres, postes accessibles, jeton) sans changer ce point d'accès.</p>
 */
public record McpCallContext(AuthenticatedUser user) {

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
}
