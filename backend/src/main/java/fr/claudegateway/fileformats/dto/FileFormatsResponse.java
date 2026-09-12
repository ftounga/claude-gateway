package fr.claudegateway.fileformats.dto;

/**
 * Formats acceptés par le serveur, par chemin de dépôt (F-85 / SF-85-01).
 *
 * @param documents   dépôt d'un document dans la bibliothèque (pipeline OCR, {@code app.ocr})
 * @param attachments pièce jointe d'une conversation ({@code app.upload})
 */
public record FileFormatsResponse(
        FileFormatProfileResponse documents,
        FileFormatProfileResponse attachments) {
}
