package fr.claudegateway.ai;

/**
 * Le fournisseur IA n'est pas configuré (ex. clé plateforme absente de l'environnement).
 * Traduite en {@code 503 provider_unavailable} par le handler global.
 */
public class AIProviderUnavailableException extends RuntimeException {

    public AIProviderUnavailableException(String message) {
        super(message);
    }
}
