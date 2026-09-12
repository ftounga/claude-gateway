package fr.claudegateway.docx;

/**
 * Un contenu soumis comme document Word n'en est pas un, ou n'est pas lisible (F-86 / SF-86-01).
 *
 * <p><b>Le message est destiné à l'utilisateur.</b> Il dit ce qui s'est passé et, quand c'est
 * possible, quoi faire — jamais un chemin, un nom de classe ni le message de l'exception d'origine.
 * La cause technique reste attachée en {@code cause} pour le journal, et n'est jamais rendue :
 * c'est la règle « aucune stacktrace dans les réponses API » de la review checklist.
 */
public class InvalidDocxException extends RuntimeException {

    public InvalidDocxException(String message) {
        super(message);
    }

    public InvalidDocxException(String message, Throwable cause) {
        super(message, cause);
    }
}
