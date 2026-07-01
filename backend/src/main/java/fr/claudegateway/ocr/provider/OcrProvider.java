package fr.claudegateway.ocr.provider;

/**
 * Couche d'abstraction du fournisseur OCR (F-05). Le code métier ({@code DocumentService}) dépend
 * <b>exclusivement</b> de cette interface, jamais d'un SDK spécifique (AWS Textract, …) — cf.
 * Provider Independence (ARCHITECTURE.md). Cela garantit le remplacement du fournisseur OCR sans
 * réécriture du domaine.
 *
 * <p>Deux régimes d'extraction :</p>
 * <ul>
 *   <li><b>Synchrone</b> ({@link #extractSync}) : images (PNG/JPEG). Un appel unique, résultat immédiat.</li>
 *   <li><b>Asynchrone</b> ({@link #startAsync} + {@link #pollAsync}) : PDF/TIFF. Le traitement lourd
 *       n'est jamais fait dans le thread HTTP : on soumet un job puis on interroge son avancement
 *       depuis un worker (SF-05-02).</li>
 * </ul>
 *
 * <p>Contrat d'erreurs : une indisponibilité de configuration lève
 * {@link OcrProviderUnavailableException} ; toute autre défaillance amont lève
 * {@link OcrProviderException}. Aucun détail brut du fournisseur (ni secret) ne doit remonter.</p>
 */
public interface OcrProvider {

    /**
     * Extraction OCR synchrone d'une image.
     *
     * @param document nom, type MIME et contenu binaire
     * @return le texte extrait et la réponse brute du fournisseur
     * @throws OcrProviderUnavailableException si le fournisseur n'est pas configuré/disponible
     * @throws OcrProviderException            en cas d'échec de l'appel amont
     */
    OcrExtraction extractSync(OcrDocument document);

    /**
     * Soumet un document pour extraction OCR asynchrone.
     *
     * @param document nom, type MIME et contenu binaire
     * @return l'identifiant de job du fournisseur (interne, jamais exposé au client)
     * @throws OcrProviderUnavailableException si le fournisseur n'est pas configuré/disponible
     * @throws OcrProviderException            en cas d'échec de la soumission
     */
    String startAsync(OcrDocument document);

    /**
     * Interroge l'avancement d'un job asynchrone.
     *
     * @param jobId identifiant de job renvoyé par {@link #startAsync}
     * @return l'état courant et, si terminé avec succès, le texte + le brut
     * @throws OcrProviderUnavailableException si le fournisseur n'est pas configuré/disponible
     * @throws OcrProviderException            en cas d'échec de l'interrogation
     */
    OcrJobResult pollAsync(String jobId);
}
