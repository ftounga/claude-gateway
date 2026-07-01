package fr.claudegateway.auth;

/**
 * Levée lorsqu'un token de réinitialisation de mot de passe est inconnu, expiré ou déjà consommé.
 * Traduite en {@code 400 Bad Request} (code {@code invalid_token}) par le {@code GlobalExceptionHandler}.
 */
public class InvalidPasswordResetTokenException extends RuntimeException {

    public InvalidPasswordResetTokenException() {
        super("Lien de réinitialisation invalide ou expiré.");
    }
}
