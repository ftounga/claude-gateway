package fr.claudegateway.auth;

/**
 * Levée lorsqu'une inscription cible un email déjà rattaché à un compte existant.
 * Traduite en {@code 409 Conflict} par le {@code GlobalExceptionHandler}.
 */
public class EmailAlreadyUsedException extends RuntimeException {

    public EmailAlreadyUsedException() {
        super("Un compte existe déjà pour cet email.");
    }
}
