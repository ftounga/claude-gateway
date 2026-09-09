package fr.claudegateway.help;

/**
 * L'utilisateur a dépassé le nombre de questions d'aide autorisées sur la fenêtre glissante
 * (F-54 / SF-54-01). Traduite en {@code 429} par le gestionnaire d'exceptions global.
 */
public class HelpRateLimitExceededException extends RuntimeException {

    public HelpRateLimitExceededException(String message) {
        super(message);
    }
}
