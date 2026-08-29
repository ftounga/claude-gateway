package fr.claudegateway.runner;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vérifie un jeton runner présenté (F-38 / SF-38-01) et en résout l'{@link RunnerIdentity}. Un jeton
 * inconnu, expiré ou révoqué ne renvoie aucune identité. Livré ici, branché sur le canal WebSocket
 * en SF-38-02.
 */
@Component
public class RunnerTokenAuthenticator {

    private final RunnerTokenRepository tokenRepository;
    private final TokenHasher tokenHasher;

    public RunnerTokenAuthenticator(RunnerTokenRepository tokenRepository, TokenHasher tokenHasher) {
        this.tokenRepository = tokenRepository;
        this.tokenHasher = tokenHasher;
    }

    /** Identité runner si le jeton est valide à l'instant présent, vide sinon. */
    @Transactional(readOnly = true)
    public Optional<RunnerIdentity> authenticate(String clearToken) {
        if (clearToken == null || clearToken.isBlank()) {
            return Optional.empty();
        }
        return tokenRepository.findByTokenHash(tokenHasher.sha256Hex(clearToken))
                .filter(token -> token.isValidAt(OffsetDateTime.now()))
                .map(token -> new RunnerIdentity(token.getId(), token.getUserId(), token.getWorkspaceId()));
    }
}
