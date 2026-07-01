package fr.claudegateway.ocr;

/**
 * Levée lorsqu'un document n'existe pas ou n'appartient pas à l'utilisateur courant. Mappée en 404
 * (indiscernable : ne révèle jamais l'existence d'un document d'un autre utilisateur).
 */
public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(String message) {
        super(message);
    }
}
