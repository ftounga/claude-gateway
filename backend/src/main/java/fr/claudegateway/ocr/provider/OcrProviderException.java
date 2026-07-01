package fr.claudegateway.ocr.provider;

/** Défaillance amont du fournisseur OCR (appel en échec). Message métier neutre, sans détail brut. */
public class OcrProviderException extends RuntimeException {

    public OcrProviderException(String message) {
        super(message);
    }

    public OcrProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
