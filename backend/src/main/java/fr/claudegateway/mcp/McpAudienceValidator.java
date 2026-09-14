package fr.claudegateway.mcp;

import java.util.List;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Vérifie que le jeton d'accès présenté à {@code /api/mcp} est bien <b>lié à cette ressource</b>
 * (RFC 8707) : son claim {@code aud} doit contenir l'identifiant de la ressource MCP. Un jeton émis
 * pour une autre ressource est refusé — c'est le test d'attaque « jeton d'une autre ressource ».
 */
public class McpAudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String requiredAudience;

    public McpAudienceValidator(String requiredAudience) {
        this.requiredAudience = requiredAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> audiences = token.getAudience();
        if (audiences != null && audiences.contains(requiredAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                "Le jeton n'est pas destiné à la ressource " + requiredAudience,
                "https://datatracker.ietf.org/doc/html/rfc8707"));
    }
}
