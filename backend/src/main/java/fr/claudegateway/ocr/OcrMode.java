package fr.claudegateway.ocr;

/** Régime d'extraction OCR retenu pour un document, selon son type MIME. */
public enum OcrMode {
    /** Images (PNG/JPEG) : extraction synchrone immédiate. */
    SYNC,
    /** PDF/TIFF : soumission d'un job asynchrone puis polling. */
    ASYNC
}
