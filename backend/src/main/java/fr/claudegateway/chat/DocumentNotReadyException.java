package fr.claudegateway.chat;

/**
 * Levée lorsqu'un document de la bibliothèque personnelle (F-08) est demandé comme contexte de chat
 * (F-24) alors que son texte n'a pas encore été extrait (statut amont à {@code EXTRACTED} ou
 * extraction en échec). Mappée en 409 : la ressource existe mais n'est pas exploitable en l'état.
 */
public class DocumentNotReadyException extends RuntimeException {

    public DocumentNotReadyException(String message) {
        super(message);
    }
}
