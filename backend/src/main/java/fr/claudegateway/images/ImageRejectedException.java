package fr.claudegateway.images;

/** Une contrainte de validation d'une génération d'image n'est pas tenue (F-142 / SF-142-04). */
public class ImageRejectedException extends RuntimeException {

    public ImageRejectedException(String message) {
        super(message);
    }
}
