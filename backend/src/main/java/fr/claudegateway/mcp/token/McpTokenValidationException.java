package fr.claudegateway.mcp.token;

/**
 * Demande de création de jeton personnel MCP invalide (F-112 / SF-112-03) : nom vide, expiration
 * absente/hors bornes, périmètre inconnu ou réservé, poste inexistant. Mappée en 400.
 */
public class McpTokenValidationException extends RuntimeException {

    public McpTokenValidationException(String message) {
        super(message);
    }
}
