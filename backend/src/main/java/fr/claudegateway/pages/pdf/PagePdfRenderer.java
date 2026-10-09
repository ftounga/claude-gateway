package fr.claudegateway.pages.pdf;

import java.util.List;

/**
 * Le moteur qui imprime un lot en PDF (F-184) — le service de rendu du cluster (SF-184-01).
 *
 * <p>Une interface pour que l'assemblage et la route se testent sans chromium.</p>
 */
public interface PagePdfRenderer {

    /** L'origine virtuelle de la page dans le moteur : un nom relatif s'y résout. */
    String ORIGIN = "https://page.cg.local";

    /** Une ressource du lot : son adresse, son type, ses octets. */
    record Resource(String url, String contentType, byte[] body) {
    }

    /** Le PDF imprimé, et les adresses que le moteur a refusées faute de les trouver dans le lot. */
    record Printed(byte[] pdf, String missing) {
    }

    /** Vrai si le moteur est configuré sur cette installation. */
    boolean isAvailable();

    /**
     * Imprime le lot.
     *
     * @throws PagePdfUnavailableException si le moteur est absent, muet ou trop lent
     * @throws PagePdfRejectedException    s'il refuse le lot
     */
    Printed print(String html, List<Resource> resources);
}
