package fr.claudegateway.ocr.provider;

/**
 * Document neutre transmis à la couche OCR (F-05). Ne porte que ce qui est nécessaire à
 * l'extraction : aucun identifiant utilisateur ni métadonnée de persistance (le domaine reste
 * responsable de l'isolation {@code user_id}).
 *
 * @param filename  nom d'origine (traçabilité)
 * @param mediaType type MIME
 * @param content   contenu binaire
 */
public record OcrDocument(String filename, String mediaType, byte[] content) {
}
