package fr.claudegateway.ocr;

/**
 * Cycle de vie d'un document dans le pipeline documentaire (OCR F-05 → ingestion RAG F-06).
 */
public enum DocumentStatus {
    /** Créé, avant traitement OCR. */
    UPLOADED,
    /** Job OCR asynchrone soumis, en attente de complétion (worker de polling, SF-05-02). */
    PROCESSING,
    /** Texte extrait avec succès (OCR terminé) ; prêt pour l'ingestion RAG (F-06). */
    EXTRACTED,
    /** Ingestion RAG en cours (chunking + embeddings), F-06 / SF-06-01. */
    INDEXING,
    /** Document indexé (chunks vectorisés persistés), F-06. */
    INDEXED,
    /** Échec de l'extraction OCR ou de l'indexation RAG (message d'erreur neutre porté par le document). */
    FAILED
}
