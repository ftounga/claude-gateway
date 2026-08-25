package fr.claudegateway.git;

/**
 * Levée lorsqu'un jeton GitHub est refusé : vide/trop long côté format, ou rejeté par GitHub
 * (invalide, révoqué, expiré). Traduite en {@code 400 invalid_git_token}. Ne porte jamais le jeton.
 */
public class InvalidGitTokenException extends RuntimeException {

    public InvalidGitTokenException(String message) {
        super(message);
    }
}
