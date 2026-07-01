package fr.claudegateway.ocr.provider;

/**
 * Résultat d'une extraction OCR : texte concaténé et réponse brute du fournisseur.
 *
 * @param text    texte extrait (lignes concaténées)
 * @param rawJson représentation brute renvoyée par le fournisseur (audit / reprocessing F-06),
 *                stockée dans {@code documents.textract_raw}
 */
public record OcrExtraction(String text, String rawJson) {
}
