package fr.claudegateway.pages.pdf;

/** Le moteur PDF est absent, muet ou trop lent (F-184 / SF-184-02) : rien à voir avec la page — 503. */
public class PagePdfUnavailableException extends RuntimeException {

    public PagePdfUnavailableException(String message) {
        super(message);
    }
}
