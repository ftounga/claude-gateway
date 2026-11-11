package fr.claudegateway.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

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
    static final String CLAIM_TOKEN_VERSION = "tv";
    /** F-232 / SF-232-05 — la portée d'une session restreinte ; absente d'une session ordinaire. */
    public static final String CLAIM_SCOPE = "scope";
    /** F-232 / SF-232-05 — le compagnon Android qui a ouvert la session (révocable avec lui). */
    static final String CLAIM_COMPANION = "cid";
    /** F-232 / SF-232-06 — le téléphone était-il verrouillé à l'émission de la session ? */
    static final String CLAIM_LOCKED = "lk";
    /** F-232 / SF-232-05 — la seule portée restreinte connue : l'écran Jarvis ouvert par le compagnon. */
    public static final String SCOPE_JARVIS_COMPANION = "jarvis-compagnon";
    /** F-257 / SF-257-02 — le ticket de session vocale, présenté par le service {@code jarvis-voice}. */
    public static final String SCOPE_JARVIS_VOICE = "jarvis-voice";
    /** F-257 / SF-257-02 — le sujet de la conversation vocale, vérifié sous {@code user_id} à l'émission. */
    public static final String CLAIM_VOICE_WORKSPACE = "ws";

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
                .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiration)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * F-232 / SF-232-05 — une session <b>courte, à portée {@value #SCOPE_JARVIS_COMPANION}</b>, émise au compagnon Android
     * appairé contre son jeton dédié. Mêmes {@code sub} / {@code tv} qu'une session ordinaire (logout-all la coupe aussi),
     * plus le compagnon ({@code cid}, révocation immédiate) et l'état verrouillé à l'émission ({@code lk}). Le filtre
     * d'authentification la borne à la liste fermée des routes de l'écran Jarvis.
     */
    public String generateCompanionToken(User user, UUID companionId, boolean locked, Instant expiresAt) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion())
                .claim(CLAIM_SCOPE, SCOPE_JARVIS_COMPANION)
                .claim(CLAIM_COMPANION, companionId.toString())
                .claim(CLAIM_LOCKED, locked)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }

    /**
     * F-257 / SF-257-02 — un <b>ticket de session vocale</b>, portée {@value #SCOPE_JARVIS_VOICE} : mêmes {@code sub} /
     * {@code tv} qu'une session ordinaire (logout-all le coupe), le sujet vérifié ({@code ws}, absent sans sujet). Le
     * filtre d'authentification le borne à la liste fermée {@link VoiceTicketScope}.
     */
    public String generateVoiceTicket(User user, UUID workspaceId, Instant expiresAt) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(user.getId().toString())
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion())
                .claim(CLAIM_SCOPE, SCOPE_JARVIS_VOICE);
        if (workspaceId != null) {
            builder.claim(CLAIM_VOICE_WORKSPACE, workspaceId.toString());
        }
        return builder.issuedAt(Date.from(now)).expiration(Date.from(expiresAt)).signWith(signingKey).compact();
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
