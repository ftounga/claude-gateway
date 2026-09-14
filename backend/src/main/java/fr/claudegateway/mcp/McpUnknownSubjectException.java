package fr.claudegateway.mcp;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * Le jeton d'accès MCP est valide mais son sujet ({@code sub}) ne désigne aucun utilisateur connu.
 * Traité comme {@code invalid_token} → 401 par le serveur de ressources.
 */
public class McpUnknownSubjectException extends OAuth2AuthenticationException {

    public McpUnknownSubjectException(String message) {
        super(new OAuth2Error("invalid_token", message, null));
    }
}
