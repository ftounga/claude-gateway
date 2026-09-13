package fr.claudegateway.pages;

/**
 * Une page refusée à la publication (F-109 / SF-109-01) : titre, taille, pièce jointe. Le message dit
 * <b>quoi corriger</b> — il est rendu à l'agent, qui peut republier.
 */
public class PageRejectedException extends RuntimeException {

    public PageRejectedException(String message) {
        super(message);
    }
}
