package fr.claudegateway.runner;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.auth.SecureTokenGenerator;
import fr.claudegateway.runner.host.RunnerHostNotFoundException;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * Cycle de vie des jetons runner (F-38 / SF-38-01) : émission (au moment de l'appairage), listing et
 * révocation. Le jeton est stocké <b>haché</b> ({@link TokenHasher}) ; sa valeur en clair n'existe
 * qu'au retour de {@link #issue}.
 *
 * <p>Depuis F-48 / SF-48-01, un jeton appartient à un <b>poste</b> et non plus à un projet : toute
 * opération de gestion vérifie l'appartenance du poste à l'utilisateur (isolation {@code user_id}).</p>
 */
@Service
public class RunnerTokenService {

    private final RunnerTokenRepository tokenRepository;
    private final TokenHasher tokenHasher;
    private final SecureTokenGenerator tokenGenerator;
    private final RunnerHostService hostService;
    private final Duration tokenTtl;

    public RunnerTokenService(
            RunnerTokenRepository tokenRepository,
            TokenHasher tokenHasher,
            SecureTokenGenerator tokenGenerator,
            RunnerHostService hostService,
            @Value("${app.runner.token-ttl:P30D}") Duration tokenTtl) {
        this.tokenRepository = tokenRepository;
        this.tokenHasher = tokenHasher;
        this.tokenGenerator = tokenGenerator;
        this.hostService = hostService;
        this.tokenTtl = tokenTtl;
    }

    /**
     * Émet un nouveau jeton runner pour un couple utilisateur/poste. Renvoie le clair (à ne révéler
     * qu'une fois) et l'entité persistée (hachée).
     */
    @Transactional
    public IssuedToken issue(UUID userId, UUID hostId, String label) {
        String clear = tokenGenerator.generate();
        RunnerToken token = tokenRepository.save(RunnerToken.builder()
                .userId(userId)
                .hostId(hostId)
                .tokenHash(tokenHasher.sha256Hex(clear))
                .label(normalizeLabel(label))
                .expiresAt(OffsetDateTime.now().plus(tokenTtl))
                .build());
        return new IssuedToken(clear, token);
    }

    /** Jetons d'un poste (isolation {@code user_id}), les plus récents d'abord. */
    @Transactional(readOnly = true)
    public List<RunnerToken> list(UUID userId, UUID hostId) {
        hostService.requireOwned(userId, hostId);
        return tokenRepository.findByUserIdAndHostIdOrderByCreatedAtDesc(userId, hostId);
    }

    /**
     * Révoque un jeton. Idempotent : révoquer un jeton déjà révoqué ne fait rien. Un jeton d'un
     * autre utilisateur ou d'un autre poste est traité comme introuvable (404).
     */
    @Transactional
    public void revoke(UUID userId, UUID hostId, UUID tokenId) {
        hostService.requireOwned(userId, hostId);
        RunnerToken token = tokenRepository.findByIdAndUserId(tokenId, userId)
                .filter(t -> t.getHostId().equals(hostId))
                .orElseThrow(() -> new RunnerHostNotFoundException(
                        "Jeton runner introuvable : " + tokenId));
        if (token.getRevokedAt() == null) {
            token.setRevokedAt(OffsetDateTime.now());
        }
    }

    private static String normalizeLabel(String label) {
        if (label == null) {
            return null;
        }
        String trimmed = label.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Résultat d'émission : le clair (éphémère) et l'entité persistée. */
    public record IssuedToken(String clearToken, RunnerToken token) {
    }
}
