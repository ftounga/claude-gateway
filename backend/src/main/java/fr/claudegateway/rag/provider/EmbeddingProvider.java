package fr.claudegateway.rag.provider;

import java.util.List;

/**
 * Couche d'abstraction du fournisseur d'embeddings (F-06). Le code métier
 * ({@code IngestionService}) dépend <b>exclusivement</b> de cette interface, jamais d'un SDK
 * spécifique (OpenAI, Voyage, …) — cf. Provider Independence (ARCHITECTURE.md). Cela garantit le
 * remplacement du fournisseur d'embeddings sans réécriture du domaine.
 *
 * <p>Contrat d'erreurs : une indisponibilité de configuration (clé absente) lève
 * {@link EmbeddingProviderUnavailableException} ; toute autre défaillance amont lève
 * {@link EmbeddingProviderException}. Aucun détail brut du fournisseur (ni secret) ne doit remonter.</p>
 */
public interface EmbeddingProvider {

    /** Dimension des vecteurs produits (doit correspondre au type {@code vector(N)} en base). */
    int dimension();

    /**
     * Calcule les embeddings d'un lot de textes.
     *
     * @param texts textes à vectoriser (ordre préservé)
     * @return un vecteur par texte, dans le même ordre ; chaque vecteur de longueur {@link #dimension()}
     * @throws EmbeddingProviderUnavailableException si le fournisseur n'est pas configuré/disponible
     * @throws EmbeddingProviderException            en cas d'échec de l'appel amont
     */
    List<float[]> embed(List<String> texts);
}
