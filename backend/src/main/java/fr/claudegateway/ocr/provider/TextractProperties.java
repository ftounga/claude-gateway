package fr.claudegateway.ocr.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration de l'implémentation OCR AWS Textract (F-05). Toutes les valeurs sont externalisées.
 * Aucune clé/secret n'est portée ici : l'accès AWS repose sur la chaîne de credentials par défaut
 * (IRSA en cluster), jamais sur des secrets en dur ni journalisés.
 *
 * @param region   région AWS des services Textract/S3 (ex. {@code eu-west-3})
 * @param s3Bucket bucket S3 pour les documents soumis en OCR asynchrone (PDF/TIFF). Obligatoire
 *                 pour l'asynchrone (Textract exige un objet S3) ; absent ⇒ async indisponible.
 * @param s3Prefix préfixe de clé S3 des documents soumis
 */
@ConfigurationProperties(prefix = "app.ocr.textract")
public record TextractProperties(String region, String s3Bucket, String s3Prefix) {

    public TextractProperties {
        if (region == null || region.isBlank()) {
            region = "eu-west-3";
        }
        if (s3Prefix == null) {
            s3Prefix = "ocr-inbound/";
        }
    }

    /** Vrai si l'asynchrone (S3 + StartDocumentTextDetection) est configuré. */
    public boolean isAsyncConfigured() {
        return s3Bucket != null && !s3Bucket.isBlank();
    }
}
