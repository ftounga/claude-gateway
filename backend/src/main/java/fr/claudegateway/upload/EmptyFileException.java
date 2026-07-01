package fr.claudegateway.upload;

/** Levée lorsqu'aucun fichier n'est fourni ou que le fichier est vide. Mappée en 400. */
public class EmptyFileException extends RuntimeException {

    public EmptyFileException(String message) {
        super(message);
    }
}
