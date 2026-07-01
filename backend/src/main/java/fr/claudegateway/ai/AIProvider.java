package fr.claudegateway.ai;

/**
 * Couche d'abstraction du fournisseur IA (cf. {@code PROJECT.md §13.5}).
 *
 * <p>Le code métier (services chat) dépend <b>exclusivement</b> de cette interface, jamais d'une
 * implémentation spécifique à Anthropic. Cela garantit l'arrivée future d'autres fournisseurs
 * (OpenAI, Gemini, …) sans réécriture du domaine.</p>
 *
 * <p>Contrat d'erreurs : une indisponibilité de configuration (clé absente) lève
 * {@link AIProviderUnavailableException} ; toute autre défaillance amont lève
 * {@link AIProviderException}. Les détails bruts du fournisseur ne remontent jamais au client.</p>
 */
public interface AIProvider {

    /**
     * Exécute une complétion (proxy vers le fournisseur IA).
     *
     * @param request modèle + historique de conversation
     * @return la réponse assistant et les métadonnées d'usage
     * @throws AIProviderUnavailableException si le fournisseur n'est pas configuré/disponible
     * @throws AIProviderException            en cas d'échec de l'appel amont
     */
    ChatCompletionResult complete(ChatCompletionRequest request);
}
