package fr.claudegateway.atelier;

/**
 * Levée lorsqu'un chemin de fichier est invalide ou tente une traversée hors du workspace
 * ({@code ..}, chemin absolu). Mappée en 400. Message métier neutre.
 */
public class InvalidFilePathException extends RuntimeException {

    public InvalidFilePathException(String message) {
        super(message);
    }
}
