package fr.claudegateway.auth;

/**
 * Levée lorsqu'un token de vérification d'e-mail est inconnu, expiré ou déjà consommé.
 * Traduite en {@code 400 Bad Request} (code {@code invalid_token}) par le {@code GlobalExceptionHandler}.
 */
public class InvalidVerificationTokenException extends RuntimeException {

    public InvalidVerificationTokenException() {
        super("Lien de vérification invalide ou expiré.");
    }
}
