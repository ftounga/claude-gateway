package fr.claudegateway.ai;

/**
 * Échec d'un appel au fournisseur IA (timeout, erreur amont, réponse invalide).
 * Traduite en {@code 502 provider_error} par le handler global — jamais exposée telle quelle.
 */
public class AIProviderException extends RuntimeException {

    public AIProviderException(String message) {
        super(message);
    }

    public AIProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
