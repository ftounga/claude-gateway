package fr.claudegateway.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import fr.claudegateway.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Émission et validation des JWT de la plateforme (HS256, secret symétrique).
 *
 * <p>Le secret provient exclusivement de la configuration ({@code APP_JWT_SECRET}) : aucun
 * secret par défaut n'est codé en dur. Si le secret est absent ou trop court au démarrage,
 * la construction du bean échoue (<b>fail-fast</b>) et l'application ne démarre pas.</p>
 *
 * <p>Le claim {@code sub} porte l'identifiant utilisateur ({@code user_id}) ; il est la seule
 * source d'identité exploitée par le filtre d'authentification.</p>
 */
@Service
public class JwtService {

    static final String CLAIM_EMAIL = "email";
    static final String CLAIM_ROLE = "role";

    private final SecretKey signingKey;
    private final Duration expiration;

    public JwtService(
            @Value("${app.jwt.secret:}") String secret,
            @Value("${app.jwt.expiration:PT24H}") Duration expiration) {
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException(
                    "APP_JWT_SECRET manquant : le secret JWT doit être fourni via la configuration (aucun défaut en dur).");
        }
        // Lève WeakKeyException au démarrage si le secret fait moins de 256 bits (fail-fast).
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
    }

    /** Émet un JWT signé pour l'utilisateur donné (sub = id, + claims email/role). */
    public String generateToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiration)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Valide la signature et l'expiration du token puis retourne ses claims.
     *
     * @throws JwtException si le token est malformé, altéré ou expiré
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
