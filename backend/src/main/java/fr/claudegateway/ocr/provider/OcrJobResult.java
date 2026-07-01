package fr.claudegateway.ocr.provider;

/**
 * Résultat d'une interrogation de job OCR asynchrone.
 *
 * @param status  état courant du job
 * @param text    texte extrait si {@link OcrJobStatus#SUCCEEDED}, sinon {@code null}
 * @param rawJson brut du fournisseur si {@link OcrJobStatus#SUCCEEDED}, sinon {@code null}
 */
public record OcrJobResult(OcrJobStatus status, String text, String rawJson) {

    public static OcrJobResult inProgress() {
        return new OcrJobResult(OcrJobStatus.IN_PROGRESS, null, null);
    }

    public static OcrJobResult succeeded(String text, String rawJson) {
        return new OcrJobResult(OcrJobStatus.SUCCEEDED, text, rawJson);
    }

    public static OcrJobResult failed() {
        return new OcrJobResult(OcrJobStatus.FAILED, null, null);
    }
}
