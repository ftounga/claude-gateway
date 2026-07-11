package fr.claudegateway.atelier;

/**
 * Levée lorsqu'une archive zip est invalide ou dépasse les garde-fous (illisible, trop d'entrées,
 * taille décompressée excessive — anti zip-bomb). Mappée en 400. Message métier neutre.
 */
public class InvalidArchiveException extends RuntimeException {

    public InvalidArchiveException(String message) {
        super(message);
    }
}
