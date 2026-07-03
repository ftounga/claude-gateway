package fr.claudegateway.chat.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.upload.UploadedFile;

/**
 * Vue d'un fichier téléversé rattaché à une conversation (F-23). Expose uniquement les métadonnées
 * nécessaires à l'affichage du dossier — jamais l'identifiant fournisseur ({@code providerFileId})
 * ni le {@code userId}.
 */
public record ConversationFileResponse(
        UUID id,
        String filename,
        String mediaType,
        long sizeBytes,
        OffsetDateTime createdAt) {

    public static ConversationFileResponse from(UploadedFile file) {
        return new ConversationFileResponse(
                file.getId(),
                file.getFilename(),
                file.getMediaType(),
                file.getSizeBytes(),
                file.getCreatedAt());
    }
}
