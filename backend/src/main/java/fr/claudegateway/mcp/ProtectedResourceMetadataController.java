package fr.claudegateway.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Métadonnée de ressource protégée (RFC 9728) du serveur MCP (F-112 / SF-112-02).
 *
 * <p>Publiée à {@code /api/.well-known/oauth-protected-resource}, elle indique au client IA la
 * ressource ({@code /api/mcp}), le(s) serveur(s) d'autorisation à interroger et les périmètres
 * disponibles. C'est le point de départ du parcours OAuth, référencé par le {@code WWW-Authenticate}
 * du 401 sur {@code /api/mcp}.</p>
 */
@RestController
public class ProtectedResourceMetadataController {

    private final McpResourceProperties resourceProperties;

    public ProtectedResourceMetadataController(McpResourceProperties resourceProperties) {
        this.resourceProperties = resourceProperties;
    }

    @GetMapping("/.well-known/oauth-protected-resource")
    public Map<String, Object> metadata(HttpServletRequest request) {
        String authorizationServer = ServletUriComponentsBuilder.fromContextPath(request)
                .build().toUriString();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resource", resourceProperties.resource());
        metadata.put("authorization_servers", List.of(authorizationServer));
        metadata.put("scopes_supported", List.copyOf(McpScopes.all()));
        metadata.put("bearer_methods_supported", List.of("header"));
        return metadata;
    }
}
