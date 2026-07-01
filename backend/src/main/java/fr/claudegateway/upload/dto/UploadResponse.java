package fr.claudegateway.upload.dto;

import java.util.UUID;

import fr.claudegateway.upload.UploadedFile;

/**
 * Vue publique d'un fichier téléversé. N'expose jamais l'identifiant fournisseur ni aucune clé.
 */
public record UploadResponse(UUID id, String filename, String mediaType, long sizeBytes) {

    public static UploadResponse from(UploadedFile file) {
        return new UploadResponse(file.getId(), file.getFilename(), file.getMediaType(), file.getSizeBytes());
    }
}
