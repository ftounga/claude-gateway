package fr.claudegateway.runner;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.user.UserRepository;

/**
 * Vérifie un jeton runner présenté (F-38 / SF-38-01) et en résout l'{@link RunnerIdentity}. Un jeton
 * inconnu, expiré, révoqué <b>ou dont le propriétaire n'existe plus</b> ne renvoie aucune identité.
 * Livré ici, branché sur le canal WebSocket en SF-38-02 et sur le repli long-polling en SF-38-09.
 *
 * <p><b>Pourquoi vérifier l'existence de l'utilisateur</b> (SF-38-14) : la purge du domaine runner à
 * la suppression du compte est le premier rempart, mais elle ne couvre pas un jeton présenté avant
 * que cette purge n'existe, ni une restauration partielle de sauvegarde. Un jeton ne doit jamais
 * authentifier au nom d'un compte effacé — le contrôle est donc fait ici, à chaque présentation.</p>
 */
@Component
public class RunnerTokenAuthenticator {

    private final RunnerTokenRepository tokenRepository;
    private final TokenHasher tokenHasher;
    private final UserRepository userRepository;

    public RunnerTokenAuthenticator(RunnerTokenRepository tokenRepository, TokenHasher tokenHasher,
            UserRepository userRepository) {
        this.tokenRepository = tokenRepository;
        this.tokenHasher = tokenHasher;
        this.userRepository = userRepository;
    }

    /** Identité runner si le jeton est valide à l'instant présent, vide sinon. */
    @Transactional(readOnly = true)
    public Optional<RunnerIdentity> authenticate(String clearToken) {
        if (clearToken == null || clearToken.isBlank()) {
            return Optional.empty();
        }
        return tokenRepository.findByTokenHash(tokenHasher.sha256Hex(clearToken))
                .filter(token -> token.isValidAt(OffsetDateTime.now()))
                // Le propriétaire doit exister : un compte supprimé n'authentifie plus rien (SF-38-14).
                .filter(token -> userRepository.existsById(token.getUserId()))
                .map(token -> new RunnerIdentity(token.getId(), token.getUserId(), token.getWorkspaceId()));
    }
}
