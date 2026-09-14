package fr.claudegateway.mcp;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

/**
 * Fabrique de jetons d'accès OAuth pour les tests du serveur MCP : signe avec le {@link JWKSource}
 * du serveur d'autorisation (les mêmes clés que celles que le serveur de ressources vérifie), comme
 * le ferait le vrai serveur d'autorisation. Permet de tester le serveur de ressources (audience,
 * portées, isolation) sans dérouler tout le parcours navigateur.
 */
final class McpAccessTokens {

    private final JwtEncoder encoder;

    McpAccessTokens(JWKSource<SecurityContext> jwkSource) {
        this.encoder = new NimbusJwtEncoder(jwkSource);
    }

    /** Jeton d'accès pour un utilisateur, avec l'audience et les portées données. */
    String forUser(UUID userId, String audience, List<String> scopes) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("https://portal.ng-itconsulting.com/api")
                .subject(userId.toString())
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plus(15, ChronoUnit.MINUTES))
                .claim("scope", scopes)
                .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}
