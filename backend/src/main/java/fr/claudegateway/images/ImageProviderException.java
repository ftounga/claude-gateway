package fr.claudegateway.images;

/** L'appel au fournisseur d'images a échoué (F-142 / SF-142-04). */
public class ImageProviderException extends RuntimeException {

    public ImageProviderException(String message) {
        super(message);
    }

    public ImageProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
