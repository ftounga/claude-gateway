package fr.claudegateway.chat;

/**
 * Conversation inexistante <b>ou</b> n'appartenant pas à l'utilisateur courant.
 * Volontairement indistincte pour ne pas révéler l'existence d'une conversation d'autrui.
 * Traduite en {@code 404 not_found}.
 */
public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException() {
        super("Conversation introuvable.");
    }
}
