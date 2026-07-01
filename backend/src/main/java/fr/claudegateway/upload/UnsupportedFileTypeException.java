package fr.claudegateway.upload;

/** Levée lorsque le type MIME du fichier n'est pas dans la liste blanche. Mappée en 415. */
public class UnsupportedFileTypeException extends RuntimeException {

    public UnsupportedFileTypeException(String message) {
        super(message);
    }
}
