package fr.claudegateway.byok;

/**
 * Levée lorsqu'une clé BYOK est invalide : format incorrect ou refusée par le fournisseur lors de
 * l'appel test de validation. Traduite en {@code 400 invalid_api_key}. Ne porte jamais la clé.
 */
public class InvalidApiKeyException extends RuntimeException {

    public InvalidApiKeyException(String message) {
        super(message);
    }
}
