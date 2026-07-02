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
     * Exécute une complétion <b>en streaming</b> : relaie les fragments de texte du fournisseur au fil
     * de l'eau via {@code onDelta}, puis renvoie le résultat complet (texte concaténé + usage) une fois
     * le flux terminé. Provider-neutre : {@code onDelta} ne transporte que du texte, aucun type amont.
     *
     * @param request modèle + historique de conversation
     * @param onDelta callback appelé pour chaque fragment de texte produit (jamais {@code null})
     * @return la réponse assistant complète et les métadonnées d'usage, à la fin du flux
     * @throws AIProviderUnavailableException si le fournisseur n'est pas configuré/disponible
     * @throws AIProviderException            en cas d'échec de l'appel amont (avant ou pendant le flux)
     */
    default ChatCompletionResult streamComplete(ChatCompletionRequest request,
            java.util.function.Consumer<String> onDelta) {
        // Repli provider-neutre : un fournisseur sans streaming natif émet le résultat complet en un
        // seul fragment. Les fournisseurs supportant le streaming (Anthropic) redéfinissent cette méthode.
        ChatCompletionResult result = complete(request);
        if (result.content() != null && !result.content().isEmpty()) {
            onDelta.accept(result.content());
        }
        return result;
    }

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
