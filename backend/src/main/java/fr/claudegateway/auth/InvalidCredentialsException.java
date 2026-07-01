package fr.claudegateway.auth;

/**
 * Levée lorsqu'une connexion échoue (email inconnu, mauvais mot de passe, ou compte OAuth-only).
 *
 * <p>Le message est volontairement générique et identique dans tous les cas : il ne doit jamais
 * permettre de distinguer « email inconnu » de « mauvais mot de passe » (anti-énumération).
 * Traduite en {@code 401 Unauthorized} par le {@code GlobalExceptionHandler}.</p>
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Identifiants invalides.");
    }
}
