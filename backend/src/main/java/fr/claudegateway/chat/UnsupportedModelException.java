package fr.claudegateway.chat;

/**
 * Modèle demandé absent de la liste blanche configurée. Traduite en {@code 400 validation_error}.
 */
public class UnsupportedModelException extends RuntimeException {

    public UnsupportedModelException(String message) {
        super(message);
    }
}
