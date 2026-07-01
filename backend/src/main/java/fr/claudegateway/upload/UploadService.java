package fr.claudegateway.upload;

import java.io.IOException;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ProviderFileReference;
import fr.claudegateway.ai.ProviderFileUpload;

/**
 * Cœur de l'upload F-04 : valide la requête (présence, type MIME, taille), <b>transmet</b> le
 * fichier au fournisseur via l'interface {@link AIProvider} (jamais Anthropic en direct), puis
 * persiste uniquement les <b>métadonnées</b> ({@link UploadedFile}) portant le {@code user_id}
 * courant. Aucun OCR, aucune indexation, aucun stockage du contenu binaire (PROJECT.md §11.6).
 */
@Service
public class UploadService {

    private final AIProvider aiProvider;
    private final UploadedFileRepository uploadedFileRepository;
    private final UploadProperties properties;

    public UploadService(
            AIProvider aiProvider,
            UploadedFileRepository uploadedFileRepository,
            UploadProperties properties) {
        this.aiProvider = aiProvider;
        this.uploadedFileRepository = uploadedFileRepository;
        this.properties = properties;
    }

    /**
     * Valide puis transmet le fichier de l'utilisateur courant au fournisseur, et enregistre ses
     * métadonnées.
     *
     * @param userId utilisateur authentifié (contexte de sécurité, jamais un paramètre client)
     * @param file   partie multipart {@code file}
     * @return les métadonnées persistées du fichier téléversé
     * @throws EmptyFileException           si le fichier est absent ou vide
     * @throws UnsupportedFileTypeException si le type MIME n'est pas dans la liste blanche
     * @throws FileTooLargeException        si la taille dépasse le plafond configuré
     */
    public UploadedFile upload(UUID userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new EmptyFileException("Aucun fichier fourni.");
        }
        long size = file.getSize();
        if (size > properties.maxBytes()) {
            throw new FileTooLargeException("Fichier trop volumineux.");
        }

        String mediaType = normalizeMediaType(file.getContentType());
        if (!properties.allowedTypeSet().contains(mediaType)) {
            throw new UnsupportedFileTypeException("Type de fichier non supporté : " + mediaType);
        }

        String filename = StringUtils.cleanPath(
                StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "fichier");

        byte[] content = readContent(file);

        // Transmission au fournisseur via l'interface neutre (jamais Anthropic en direct).
        ProviderFileReference reference = aiProvider.uploadFile(
                new ProviderFileUpload(filename, mediaType, content));

        return uploadedFileRepository.save(UploadedFile.builder()
                .userId(userId)
                .providerFileId(reference.providerFileId())
                .filename(filename)
                .mediaType(mediaType)
                .sizeBytes(size)
                .build());
    }

    private static String normalizeMediaType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return "application/octet-stream";
        }
        // Ignore les paramètres éventuels (ex. "text/plain; charset=utf-8").
        int semicolon = contentType.indexOf(';');
        String base = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return base.trim().toLowerCase();
    }

    private static byte[] readContent(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new EmptyFileException("Fichier illisible.");
        }
    }
}
