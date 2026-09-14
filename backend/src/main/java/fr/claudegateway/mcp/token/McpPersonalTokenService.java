package fr.claudegateway.mcp.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.mcp.McpScopes;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.UserRole;

/**
 * Cycle de vie des jetons personnels MCP (F-112 / SF-112-03) : création (hachage, validation),
 * listage, révocation, et authentification d'un jeton présenté sur {@code /mcp}.
 *
 * <p>Le secret n'existe qu'à la création ({@link CreatedToken#secret()}), affiché une seule fois ;
 * seul son SHA-256 est stocké. L'expiration est <b>obligatoire</b> et bornée à 90 jours. Les postes
 * accessibles sont validés comme appartenant à l'utilisateur (isolation).</p>
 */
@Service
public class McpPersonalTokenService {

    /** Préfixe reconnaissable d'un jeton personnel MCP (distinct d'un JWT/jeton OAuth). */
    public static final String TOKEN_PREFIX = "cgmcp_";
    private static final int SECRET_BYTES = 32;

    private final McpPersonalTokenRepository tokenRepository;
    private final RunnerHostRepository hostRepository;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();

    public McpPersonalTokenService(
            McpPersonalTokenRepository tokenRepository, RunnerHostRepository hostRepository) {
        this.tokenRepository = tokenRepository;
        this.hostRepository = hostRepository;
    }

    /** Jeton créé : l'entité persistée et le secret en clair, renvoyé une seule fois. */
    public record CreatedToken(McpPersonalToken token, String secret) {
    }

    @Transactional
    public CreatedToken create(UUID userId, UserRole role, String name, Set<String> scopes,
            Set<UUID> hostIds, Integer expiresInDays) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty() || cleanName.length() > McpPersonalToken.MAX_NAME_LENGTH) {
            throw new McpTokenValidationException("Le nom du jeton est obligatoire (1 à "
                    + McpPersonalToken.MAX_NAME_LENGTH + " caractères).");
        }
        if (expiresInDays == null || expiresInDays < 1 || expiresInDays > McpPersonalToken.MAX_EXPIRY_DAYS) {
            throw new McpTokenValidationException("L'expiration est obligatoire, entre 1 et "
                    + McpPersonalToken.MAX_EXPIRY_DAYS + " jours.");
        }
        Set<String> cleanScopes = validateScopes(scopes, role);
        Set<UUID> cleanHosts = validateHosts(userId, hostIds);

        String secret = TOKEN_PREFIX + base64.encodeToString(randomBytes());
        String prefix = secret.substring(0, Math.min(secret.length(), TOKEN_PREFIX.length() + 4));
        OffsetDateTime now = OffsetDateTime.now();

        McpPersonalToken token = McpPersonalToken.builder()
                .userId(userId)
                .name(cleanName)
                .tokenHash(sha256Hex(secret))
                .tokenPrefix(prefix)
                .scopes(String.join(" ", cleanScopes))
                .hostIds(new HashSet<>(cleanHosts))
                .createdAt(now)
                .expiresAt(now.plusDays(expiresInDays))
                .build();
        return new CreatedToken(tokenRepository.save(token), secret);
    }

    @Transactional(readOnly = true)
    public List<McpPersonalToken> list(UUID userId) {
        List<McpPersonalToken> tokens = tokenRepository.findByUserIdOrderByCreatedAtDesc(userId);
        // Force le chargement des postes (collection LAZY) dans la transaction : la vue est
        // construite par le contrôleur, hors transaction (open-in-view désactivé).
        tokens.forEach(token -> token.getHostIds().size());
        return tokens;
    }

    @Transactional
    public void revoke(UUID userId, UUID tokenId) {
        McpPersonalToken token = tokenRepository.findByIdAndUserId(tokenId, userId)
                .orElseThrow(() -> new McpTokenNotFoundException("Jeton introuvable."));
        if (token.getRevokedAt() == null) {
            token.setRevokedAt(OffsetDateTime.now());
            tokenRepository.save(token);
        }
    }

    /** True si le porteur présenté a la forme d'un jeton personnel MCP. */
    public static boolean looksLikePersonalToken(String bearer) {
        return bearer != null && bearer.startsWith(TOKEN_PREFIX);
    }

    /**
     * Authentifie un jeton présenté : retrouvé par hachage, actif (non révoqué, non expiré). Met à
     * jour {@code last_used_at}. Vide sinon.
     */
    @Transactional
    public Optional<McpPersonalToken> authenticate(String presentedToken) {
        if (!looksLikePersonalToken(presentedToken)) {
            return Optional.empty();
        }
        return tokenRepository.findByTokenHash(sha256Hex(presentedToken))
                .filter(token -> token.isActiveAt(OffsetDateTime.now()))
                .map(token -> {
                    token.setLastUsedAt(OffsetDateTime.now());
                    McpPersonalToken saved = tokenRepository.save(token);
                    // Charge les postes dans la transaction : le filtre les lit ensuite (détaché).
                    saved.getHostIds().size();
                    return saved;
                });
    }

    private Set<String> validateScopes(Set<String> scopes, UserRole role) {
        Set<String> requested = scopes == null ? Set.of() : scopes;
        Set<String> clean = new LinkedHashSet<>();
        for (String scope : requested) {
            if (!McpScopes.all().contains(scope)) {
                throw new McpTokenValidationException("Périmètre inconnu : " + scope);
            }
            if (McpScopes.ADMIN.equals(scope) && role != UserRole.ADMIN) {
                throw new McpTokenValidationException(
                        "Le périmètre « admin » est réservé au rôle ADMIN.");
            }
            clean.add(scope);
        }
        if (clean.isEmpty()) {
            throw new McpTokenValidationException("Au moins un périmètre est obligatoire.");
        }
        return clean;
    }

    private Set<UUID> validateHosts(UUID userId, Set<UUID> hostIds) {
        Set<UUID> requested = hostIds == null ? Set.of() : hostIds;
        Set<UUID> clean = new LinkedHashSet<>();
        for (UUID hostId : requested) {
            hostRepository.findByIdAndUserId(hostId, userId)
                    .orElseThrow(() -> new McpTokenValidationException(
                            "Poste inconnu ou n'appartenant pas à l'utilisateur : " + hostId));
            clean.add(hostId);
        }
        return clean;
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return bytes;
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 indisponible", ex);
        }
    }
}
