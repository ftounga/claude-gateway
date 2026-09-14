package fr.claudegateway.mcp.token;

/**
 * Jeton personnel MCP introuvable pour cet utilisateur (F-112 / SF-112-03). Mappée en 404 : un jeton
 * d'un autre utilisateur n'existe pas de son point de vue (isolation {@code user_id}).
 */
public class McpTokenNotFoundException extends RuntimeException {

    public McpTokenNotFoundException(String message) {
        super(message);
    }
}
