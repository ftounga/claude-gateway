package fr.claudegateway.ocr.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.ocr.Document;

/**
 * Vue détaillée d'un document OCR, incluant le texte extrait. N'expose jamais le job fournisseur ni
 * le brut Textract.
 */
public record DocumentDetailResponse(
        UUID id,
        String filename,
        String mediaType,
        long sizeBytes,
        String status,
        String extractedText,
        String errorMessage,
        OffsetDateTime createdAt) {

    public static DocumentDetailResponse from(Document document) {
        return new DocumentDetailResponse(
                document.getId(),
                document.getFilename(),
                document.getMediaType(),
                document.getSizeBytes(),
                document.getStatus().name(),
                document.getExtractedText(),
                document.getErrorMessage(),
                document.getCreatedAt());
    }
}
