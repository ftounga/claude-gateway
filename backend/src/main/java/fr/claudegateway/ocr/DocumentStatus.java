package fr.claudegateway.ocr;

/**
 * Cycle de vie d'un document dans le pipeline OCR (F-05).
 *
 * <p>{@code INDEXED} (post-OCR, indexation RAG) sera ajouté par F-06 : l'énumération est ouverte à
 * l'extension sans casser F-05.</p>
 */
public enum DocumentStatus {
    /** Créé, avant traitement OCR. */
    UPLOADED,
    /** Job OCR asynchrone soumis, en attente de complétion (worker de polling, SF-05-02). */
    PROCESSING,
    /** Texte extrait avec succès (OCR terminé). */
    EXTRACTED,
    /** Échec de l'extraction OCR. */
    FAILED
}
