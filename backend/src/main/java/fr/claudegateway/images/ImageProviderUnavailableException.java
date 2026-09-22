package fr.claudegateway.images;

/**
 * Le fournisseur d'images n'est pas configuré (aucune clé) : <b>aucun appel n'est émis</b>
 * (F-142 / SF-142-04). Même doctrine que le STT « non configuré » : le tuyau reste fermé tant que le
 * secret n'est pas posé par l'environnement.
 */
public class ImageProviderUnavailableException extends RuntimeException {

    public ImageProviderUnavailableException(String message) {
        super(message);
    }
}
