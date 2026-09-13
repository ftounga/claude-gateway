package fr.claudegateway.pages;

/**
 * Page ou version introuvable <b>pour ce compte</b> (F-109). Une page inconnue et la page d'un autre
 * compte sont indiscernables : on ne dit pas à un compte qu'une page existe ailleurs.
 */
public class PageNotFoundException extends RuntimeException {

    public PageNotFoundException() {
        super("Page not found");
    }
}
