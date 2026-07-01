package fr.claudegateway.rag.provider;

/** Le fournisseur d'embeddings n'est pas configuré/disponible (ex. clé API absente). Message neutre. */
public class EmbeddingProviderUnavailableException extends RuntimeException {

    public EmbeddingProviderUnavailableException(String message) {
        super(message);
    }
}
