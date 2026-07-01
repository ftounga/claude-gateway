package fr.claudegateway.ocr;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import fr.claudegateway.ocr.provider.OcrDocument;
import fr.claudegateway.ocr.provider.OcrExtraction;
import fr.claudegateway.ocr.provider.OcrProvider;
import fr.claudegateway.ocr.provider.OcrProviderException;
import fr.claudegateway.ocr.provider.OcrProviderUnavailableException;
import fr.claudegateway.upload.EmptyFileException;
import fr.claudegateway.upload.FileTooLargeException;
import fr.claudegateway.upload.UnsupportedFileTypeException;

/**
 * Cœur du pipeline OCR (F-05 / SF-05-01) : valide un document soumis, décide du régime (image =
 * synchrone, PDF/TIFF = asynchrone), délègue l'extraction à l'interface {@link OcrProvider} (jamais
 * un SDK en direct) et persiste l'état sur l'entité {@link Document} portant le {@code user_id}.
 *
 * <p>Le contenu binaire n'est jamais conservé : seul le texte extrait et le brut fournisseur le sont.
 * Les échecs fournisseur sont enregistrés sur le document (statut {@code FAILED}, message neutre),
 * jamais propagés en stacktrace au client, jamais journalisés avec un secret ou le contenu.</p>
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documentRepository;
    private final OcrProvider ocrProvider;
    private final OcrProperties properties;

    public DocumentService(
            DocumentRepository documentRepository,
            OcrProvider ocrProvider,
            OcrProperties properties) {
        this.documentRepository = documentRepository;
        this.ocrProvider = ocrProvider;
        this.properties = properties;
    }

    /**
     * Soumet un document de l'utilisateur courant au pipeline OCR.
     *
     * @param userId utilisateur authentifié (contexte de sécurité, jamais un paramètre client)
     * @param file   partie multipart {@code file}
     * @return le document persisté (statut reflétant le traitement : EXTRACTED / PROCESSING / FAILED)
     * @throws EmptyFileException           si le fichier est absent ou vide
     * @throws UnsupportedFileTypeException si le type MIME n'est pas dans la liste blanche
     * @throws FileTooLargeException        si la taille dépasse le plafond configuré
     */
    public Document submit(UUID userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new EmptyFileException("Aucun document fourni.");
        }
        long size = file.getSize();
        if (size > properties.maxBytes()) {
            throw new FileTooLargeException("Document trop volumineux.");
        }
        String mediaType = normalizeMediaType(file.getContentType());
        if (!properties.allowedTypeSet().contains(mediaType)) {
            throw new UnsupportedFileTypeException("Type de document non supporté : " + mediaType);
        }
        String filename = StringUtils.cleanPath(
                StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "document");
        byte[] content = readContent(file);

        OcrMode mode = properties.isSyncType(mediaType) ? OcrMode.SYNC : OcrMode.ASYNC;
        Document document = Document.builder()
                .userId(userId)
                .filename(filename)
                .mediaType(mediaType)
                .sizeBytes(size)
                .status(DocumentStatus.UPLOADED)
                .ocrMode(mode)
                .build();

        OcrDocument ocrDocument = new OcrDocument(filename, mediaType, content);
        if (mode == OcrMode.SYNC) {
            extractSynchronously(document, ocrDocument);
        } else {
            submitAsynchronously(document, ocrDocument);
        }
        return documentRepository.save(document);
    }

    /** Liste des documents de l'utilisateur courant (isolation {@code user_id}). */
    public List<Document> list(UUID userId) {
        return documentRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Détail d'un document de l'utilisateur courant.
     *
     * @throws DocumentNotFoundException si le document n'existe pas ou appartient à un autre utilisateur
     */
    public Document getById(UUID userId, UUID documentId) {
        return documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new DocumentNotFoundException("Document introuvable."));
    }

    private void extractSynchronously(Document document, OcrDocument ocrDocument) {
        try {
            OcrExtraction extraction = ocrProvider.extractSync(ocrDocument);
            document.setExtractedText(extraction.text());
            document.setTextractRaw(extraction.rawJson());
            document.setStatus(DocumentStatus.EXTRACTED);
        } catch (OcrProviderUnavailableException | OcrProviderException ex) {
            // Aucun secret ni contenu n'est journalisé : type MIME uniquement.
            log.warn("OCR synchrone en échec (type={})", document.getMediaType());
            document.setStatus(DocumentStatus.FAILED);
            document.setErrorMessage("Échec de l'extraction OCR.");
        }
    }

    private void submitAsynchronously(Document document, OcrDocument ocrDocument) {
        try {
            String jobId = ocrProvider.startAsync(ocrDocument);
            document.setProviderJobId(jobId);
            document.setStatus(DocumentStatus.PROCESSING);
        } catch (OcrProviderUnavailableException | OcrProviderException ex) {
            log.warn("Soumission OCR asynchrone en échec (type={})", document.getMediaType());
            document.setStatus(DocumentStatus.FAILED);
            document.setErrorMessage("Échec de la soumission OCR.");
        }
    }

    private static String normalizeMediaType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return "application/octet-stream";
        }
        int semicolon = contentType.indexOf(';');
        String base = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return base.trim().toLowerCase();
    }

    private static byte[] readContent(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new EmptyFileException("Document illisible.");
        }
    }
}
