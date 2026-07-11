package fr.claudegateway.atelier.agent;

/**
 * Erreur lors d'un appel au fournisseur Managed Agents (F-28 / Phase 2). Le message reste neutre :
 * ni la clé API ni la réponse brute du fournisseur ne doivent y figurer.
 */
public class AgentProviderException extends RuntimeException {

    public AgentProviderException(String message) {
        super(message);
    }

    public AgentProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
