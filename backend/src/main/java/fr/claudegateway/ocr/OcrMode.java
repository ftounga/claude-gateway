package fr.claudegateway.ocr;

/** Régime d'extraction OCR retenu pour un document, selon son type MIME. */
public enum OcrMode {
    /** Images (PNG/JPEG) : extraction synchrone immédiate. */
    SYNC,
    /** PDF/TIFF : soumission d'un job asynchrone puis polling. */
    ASYNC,

    /**
     * Word ({@code .docx}) : extraction <b>sur la machine</b>, sans fournisseur (F-86 / SF-86-02).
     *
     * <p>Volontairement distinct de {@link #SYNC}, qui veut dire « OCR synchrone <i>chez le
     * fournisseur</i> ». Y ranger le {@code .docx} rendrait {@code ocr_mode} menteur et rendrait
     * indistinguables, en base, les documents partis chez Textract et ceux qui ne sont jamais
     * sortis du pod.</p>
     */
    LOCAL
}
