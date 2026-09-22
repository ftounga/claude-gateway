package fr.claudegateway.images;

/**
 * Aucune image de ce compte ne porte cet identifiant (F-142 / SF-142-04). Une image d'un autre compte est
 * <b>indiscernable</b> d'une image inexistante (isolation {@code user_id}).
 */
public class ImageNotFoundException extends RuntimeException {

    public ImageNotFoundException() {
        super("Image introuvable.");
    }
}
