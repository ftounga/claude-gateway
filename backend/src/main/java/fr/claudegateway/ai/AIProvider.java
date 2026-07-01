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

    /**
     * Transmet un fichier au fournisseur IA (relais, sans traitement documentaire côté Gateway).
     *
     * @param upload nom, type MIME et contenu du fichier à relayer
     * @return la référence neutre du fichier chez le fournisseur
     * @throws AIProviderUnavailableException si le fournisseur n'est pas configuré/disponible
     * @throws AIProviderException            en cas d'échec de l'appel amont
     */
    ProviderFileReference uploadFile(ProviderFileUpload upload);
}
