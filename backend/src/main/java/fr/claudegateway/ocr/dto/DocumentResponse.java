package fr.claudegateway.ocr.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.ocr.Document;

/**
 * Vue publique d'un document OCR. N'expose jamais le job fournisseur, le brut Textract, ni aucun secret.
 */
public record DocumentResponse(
        UUID id,
        String filename,
        String mediaType,
        long sizeBytes,
        String status,
        int chunkCount,
        OffsetDateTime createdAt) {

    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getFilename(),
                document.getMediaType(),
                document.getSizeBytes(),
                document.getStatus().name(),
                document.getChunkCount(),
                document.getCreatedAt());
    }
}
