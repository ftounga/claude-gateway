package fr.claudegateway.ocr.provider;

/** État neutre d'un job OCR asynchrone (indépendant du fournisseur). */
public enum OcrJobStatus {
    /** Job en cours chez le fournisseur ; réinterroger plus tard. */
    IN_PROGRESS,
    /** Job terminé avec succès : texte et brut disponibles. */
    SUCCEEDED,
    /** Job en échec chez le fournisseur. */
    FAILED
}
