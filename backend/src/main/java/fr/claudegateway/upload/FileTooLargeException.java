package fr.claudegateway.upload;

/** Levée lorsque la taille du fichier dépasse le plafond configuré. Mappée en 413. */
public class FileTooLargeException extends RuntimeException {

    public FileTooLargeException(String message) {
        super(message);
    }
}
