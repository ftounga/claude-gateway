package fr.claudegateway.atelier.git;

/**
 * Mode « Assistant » refusé sur un workspace adossé à un dépôt (F-31 / SF-31-03).
 *
 * <p>Le mode Assistant lit et édite les fichiers du <b>stockage objet</b> par boucle tool-use : sur un
 * projet Git ce stockage est vide au départ, et Claude répondrait sur un projet inexistant. Le mode
 * Terminal, lui, travaille dans la sandbox où le dépôt est réellement cloné.</p>
 */
public class GitWorkspaceModeException extends RuntimeException {

    public GitWorkspaceModeException(String message) {
        super(message);
    }
}
