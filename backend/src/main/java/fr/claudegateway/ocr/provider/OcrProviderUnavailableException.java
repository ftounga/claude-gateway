package fr.claudegateway.ocr.provider;

/** Le fournisseur OCR n'est pas configuré/disponible (ex. bucket S3 absent). Message neutre. */
public class OcrProviderUnavailableException extends RuntimeException {

    public OcrProviderUnavailableException(String message) {
        super(message);
    }
}
