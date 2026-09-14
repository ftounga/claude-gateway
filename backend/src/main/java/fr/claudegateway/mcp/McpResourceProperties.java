package fr.claudegateway.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration de la ressource protégée MCP (F-112 / SF-112-02).
 *
 * @param resource identifiant de la ressource {@code /api/mcp} (RFC 8707/9728) : audience exigée des
 *                 jetons d'accès et valeur annoncée dans la métadonnée de ressource protégée
 */
@ConfigurationProperties(prefix = "app.mcp")
public record McpResourceProperties(String resource) {

    public McpResourceProperties {
        if (resource == null || resource.isBlank()) {
            resource = "https://portal.ng-itconsulting.com/api/mcp";
        }
    }
}
