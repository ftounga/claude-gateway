package fr.claudegateway.pages.pdf;

/** Le moteur PDF a refusé le lot (F-184 / SF-184-02) : sa raison est relayée — 422. */
public class PagePdfRejectedException extends RuntimeException {

    public PagePdfRejectedException(String message) {
        super(message);
    }
}
