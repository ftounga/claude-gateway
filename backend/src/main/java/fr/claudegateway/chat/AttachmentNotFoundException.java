package fr.claudegateway.chat;

/**
 * Levée lorsqu'un fichier attaché à un message n'existe pas ou n'appartient pas à l'utilisateur
 * courant (isolation multi-tenant). Mappée en 404 — indiscernable d'un id inexistant pour ne pas
 * divulguer l'existence de fichiers d'autres utilisateurs.
 */
public class AttachmentNotFoundException extends RuntimeException {

    public AttachmentNotFoundException() {
        super("Pièce jointe introuvable.");
    }
}
