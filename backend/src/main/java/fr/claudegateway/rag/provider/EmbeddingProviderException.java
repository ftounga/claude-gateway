package fr.claudegateway.rag.provider;

/** Défaillance amont du fournisseur d'embeddings (appel en échec). Message métier neutre, sans détail brut. */
public class EmbeddingProviderException extends RuntimeException {

    public EmbeddingProviderException(String message) {
        super(message);
    }

    public EmbeddingProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
