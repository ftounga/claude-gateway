package fr.claudegateway.ocr.dto;

import java.util.UUID;

import fr.claudegateway.ocr.Document;

/**
 * Vue légère de l'état d'un document (F-08 / SF-08-01), dédiée au suivi/polling : ne transporte que
 * l'état de traitement, le nombre de chunks indexés et l'éventuel message d'erreur métier neutre.
 * N'expose jamais le texte extrait, le brut fournisseur, le job fournisseur ni aucun secret.
 */
public record DocumentStatusResponse(
        UUID id,
        String status,
        int chunkCount,
        String errorMessage) {

    public static DocumentStatusResponse from(Document document) {
        return new DocumentStatusResponse(
                document.getId(),
                document.getStatus().name(),
                document.getChunkCount(),
                document.getErrorMessage());
    }
}
