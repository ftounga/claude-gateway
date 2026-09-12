package fr.claudegateway.ocr;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import fr.claudegateway.docx.DocxExtraction;
import fr.claudegateway.docx.DocxTextExtractor;
import fr.claudegateway.ocr.provider.OcrDocument;
import fr.claudegateway.ocr.provider.OcrExtraction;
import fr.claudegateway.ocr.provider.OcrJobResult;
import fr.claudegateway.ocr.provider.OcrProvider;
import fr.claudegateway.ocr.provider.OcrProviderException;
import fr.claudegateway.ocr.provider.OcrProviderUnavailableException;
import fr.claudegateway.upload.EmptyFileException;
import fr.claudegateway.upload.FileTooLargeException;
import fr.claudegateway.upload.UnsupportedFileTypeException;

/**
 * Cœur du pipeline documentaire (F-05 / SF-05-01) : valide un document soumis, décide du régime,
 * délègue l'extraction et persiste l'état sur l'entité {@link Document} portant le {@code user_id}.
 *
 * <p><b>Quatre voies, pas trois</b> (F-86 / SF-86-02) :</p>
 * <ul>
 *   <li>image (PNG/JPEG) → OCR <b>synchrone</b> chez le fournisseur ({@link OcrProvider});</li>
 *   <li>PDF/TIFF → OCR <b>asynchrone</b> (job + worker de relance);</li>
 *   <li>Word ({@code .docx}) → extraction <b>locale</b> ({@link DocxTextExtractor}), qui ne passe
 *       <b>pas</b> par {@link OcrProvider} : ses deux gestes décrivent une reconnaissance de
 *       caractères sur une image, or un {@code .docx} n'est pas une image — il n'y a rien à
 *       reconnaître, seulement à lire ;</li>
 *   <li>texte → transmis tel quel par les chemins voisins.</li>
 * </ul>
 *
 * <p>Le contenu binaire n'est jamais conservé : seul le texte extrait et le brut fournisseur le sont.
 * Les échecs fournisseur sont enregistrés sur le document (statut {@code FAILED}, message neutre),
 * jamais propagés en stacktrace au client, jamais journalisés avec un secret ou le contenu.</p>
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /**
     * Les types qui ne disent rien : c'est ce que le navigateur annonce pour un fichier dont le
     * poste ne connaît pas l'extension. Seuls ceux-là déclenchent un examen du contenu.
     */
    private static final Set<String> UNDECLARED_TYPES =
            Set.of("application/octet-stream", "application/x-zip-compressed");

    private final DocumentRepository documentRepository;
    private final OcrProvider ocrProvider;
    private final DocxTextExtractor docxTextExtractor;
    private final OcrProperties properties;

    public DocumentService(
            DocumentRepository documentRepository,
            OcrProvider ocrProvider,
            DocxTextExtractor docxTextExtractor,
            OcrProperties properties) {
        this.documentRepository = documentRepository;
        this.ocrProvider = ocrProvider;
        this.docxTextExtractor = docxTextExtractor;
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
            log.info("Document refusé : aucun fichier reçu ou fichier vide");
            throw new EmptyFileException("Aucun document fourni.");
        }
        long size = file.getSize();
        long maxMb = properties.maxBytes() / (1024 * 1024);
        if (size > properties.maxBytes()) {
            // Journalisé en INFO, pas en debug : un refus d'upload est la première chose qu'on cherche
            // quand un utilisateur dit « ça ne marche pas », et un refus invisible se diagnostique à
            // l'aveugle. Type et taille suffisent — le nom du fichier est une donnée personnelle.
            log.info("Document refusé : {} octets > plafond de {} Mo (type={})", size, maxMb, file.getContentType());
            throw new FileTooLargeException(
                    "Document trop volumineux : " + (size / (1024 * 1024)) + " Mo, maximum " + maxMb + " Mo.");
        }
        String mediaType = normalizeMediaType(file.getContentType());
        byte[] content = null;
        if (!properties.allowedTypeSet().contains(mediaType) && isUndeclared(mediaType)) {
            // Seul chemin qui lit le fichier avant de l'avoir accepté, et seulement quand le type
            // déclaré ne dit rien : voir resolveByContent. Le chemin nominal est inchangé.
            content = readContent(file);
            mediaType = resolveByContent(mediaType, content);
        }
        if (!properties.allowedTypeSet().contains(mediaType)) {
            log.info("Document refusé : type « {} » hors liste blanche {}", mediaType, properties.allowedTypeSet());
            // Le type déclaré est dit à l'utilisateur : un PDF annoncé `application/octet-stream` par
            // le navigateur tombe ici, et sans cette précision le refus est incompréhensible.
            throw new UnsupportedFileTypeException("Format refusé (« " + mediaType
                    + " »). Formats acceptés : " + String.join(", ", properties.allowedTypeSet()) + ".");
        }
        String filename = StringUtils.cleanPath(
                StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "document");
        if (content == null) {
            content = readContent(file);
        }

        OcrMode mode = resolveMode(mediaType);
        Document document = Document.builder()
                .userId(userId)
                .filename(filename)
                .mediaType(mediaType)
                .sizeBytes(size)
                .status(DocumentStatus.UPLOADED)
                .ocrMode(mode)
                .build();

        if (mode == OcrMode.LOCAL) {
            // Quatrième voie (F-86) : rien ne sort de la machine, et rien n'est persisté si le
            // fichier n'est pas lisible — l'exception remonte en 422, comme un refus de type.
            extractLocally(document, content);
            return documentRepository.save(document);
        }

        OcrDocument ocrDocument = new OcrDocument(filename, mediaType, content);
        if (mode == OcrMode.SYNC) {
            extractSynchronously(document, ocrDocument);
        } else {
            submitAsynchronously(document, ocrDocument);
        }
        return documentRepository.save(document);
    }

    /**
     * Le régime d'extraction du type : <b>local d'abord</b> (F-86 : Word ne va chez aucun OCR),
     * puis le routage synchrone/asynchrone existant.
     */
    private OcrMode resolveMode(String mediaType) {
        if (properties.isLocalType(mediaType)) {
            return OcrMode.LOCAL;
        }
        return properties.isSyncType(mediaType) ? OcrMode.SYNC : OcrMode.ASYNC;
    }

    /** Vrai si le type déclaré ne dit rien de ce que le fichier est. */
    private boolean isUndeclared(String mediaType) {
        return UNDECLARED_TYPES.contains(mediaType)
                && properties.allowedTypeSet().contains(OcrProperties.DOCX_MEDIA_TYPE);
    }

    /**
     * Le type à retenir quand celui que le navigateur déclare ne vaut rien.
     *
     * <p>Un poste sans suite bureautique installée n'associe aucun type MIME à l'extension
     * {@code .docx} : le navigateur annonce {@code application/octet-stream}, ou rien. Refuser là
     * serait refuser exactement la personne que F-86 existe pour servir — celle qui découvre le
     * produit avec son premier fichier.
     *
     * <p>C'est la symétrie du garde-fou de SF-86-01 : un fichier <b>renommé</b> est refusé sur son
     * contenu, donc un fichier <b>mal déclaré</b> est admis sur son contenu. Dans les deux sens,
     * c'est le contenu qui décide, jamais le nom ni l'étiquette. Le reniflage ne s'applique
     * <b>qu'</b>à un type déclaré vide ou générique : un type déclaré et faux ({@code image/bmp}
     * pour un exécutable) n'ouvre aucune porte, et un type déclaré et accepté n'est jamais examiné.
     */
    private String resolveByContent(String declaredType, byte[] content) {
        if (docxTextExtractor.looksLikeDocx(content)) {
            log.info("Document déclaré « {} » reconnu comme document Word sur son contenu", declaredType);
            return OcrProperties.DOCX_MEDIA_TYPE;
        }
        return declaredType;
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

    /**
     * Supprime définitivement un document de l'utilisateur courant, au titre du droit à l'effacement
     * (RGPD, F-08 / SF-08-01). La résolution passe par {@link DocumentRepository#findByIdAndUserId}
     * (isolation {@code user_id} : un utilisateur ne peut jamais supprimer le document d'un autre).
     *
     * <p>Les chunks dérivés — et, en Postgres, la colonne vectorielle {@code chunks.embedding} portée
     * par ces lignes — sont supprimés en cascade au niveau base via la FK
     * {@code chunks.document_id -> documents.id ON DELETE CASCADE} (migration {@code 011}, définie
     * pour Postgres et H2). Aucun secret ni contenu documentaire n'est journalisé.</p>
     *
     * @param userId     utilisateur authentifié (contexte de sécurité, jamais un paramètre client)
     * @param documentId identifiant du document à supprimer
     * @throws DocumentNotFoundException si le document n'existe pas ou appartient à un autre utilisateur
     */
    public void delete(UUID userId, UUID documentId) {
        Document document = documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new DocumentNotFoundException("Document introuvable."));
        documentRepository.delete(document);
    }

    /**
     * Complète les jobs OCR asynchrones en attente (SF-05-02). Interroge, pour chaque document au
     * statut {@code PROCESSING}, l'avancement du job via {@link OcrProvider} et met à jour l'état.
     * Un échec d'interrogation laisse le document {@code PROCESSING} (réessayable au cycle suivant).
     * Aucun secret ni contenu n'est journalisé. Exécuté hors thread HTTP (worker planifié).
     *
     * @return le nombre de documents passés à un état terminal ({@code EXTRACTED}/{@code FAILED}) ce cycle
     */
    public int pollPendingJobs() {
        List<Document> pending = documentRepository.findByStatus(DocumentStatus.PROCESSING);
        int completed = 0;
        for (Document document : pending) {
            if (!StringUtils.hasText(document.getProviderJobId())) {
                continue; // anomalie : PROCESSING sans job — ignoré (robustesse, pas de NPE).
            }
            if (pollAndUpdate(document)) {
                completed++;
            }
        }
        return completed;
    }

    private boolean pollAndUpdate(Document document) {
        try {
            OcrJobResult result = ocrProvider.pollAsync(document.getProviderJobId());
            switch (result.status()) {
                case IN_PROGRESS -> {
                    return false; // toujours en cours : on réessaiera.
                }
                case SUCCEEDED -> {
                    document.setExtractedText(result.text());
                    document.setTextractRaw(result.rawJson());
                    document.setStatus(DocumentStatus.EXTRACTED);
                    documentRepository.save(document);
                    return true;
                }
                case FAILED -> {
                    document.setStatus(DocumentStatus.FAILED);
                    document.setErrorMessage("Échec de l'extraction OCR.");
                    documentRepository.save(document);
                    return true;
                }
                default -> {
                    return false;
                }
            }
        } catch (OcrProviderUnavailableException | OcrProviderException ex) {
            // Échec transitoire : on laisse le document PROCESSING pour réessayer. Aucun secret loggé.
            log.warn("Polling OCR en échec (document={}) — réessai au prochain cycle", document.getId());
            return false;
        }
    }

    /**
     * Extraction Word, <b>sur la machine</b> (F-86 / SF-86-02). Aucun appel à {@link OcrProvider} :
     * un {@code .docx} n'est pas une image, il n'y a rien à reconnaître, seulement à lire.
     *
     * <p>Un échec n'est <b>pas</b> enregistré en {@code FAILED} : les statuts {@code FAILED}
     * existants sont des échecs <i>fournisseur</i>, survenus après coup sur un document valide dont
     * l'utilisateur attend le résultat. Un {@code .docx} illisible est un échec <i>de la requête</i>,
     * découvert immédiatement — au même titre qu'un type refusé ou une taille excessive, deux refus
     * qui ne persistent rien. L'exception remonte donc, et le contrôleur d'erreurs la rend en 422
     * avec la phrase destinée à l'utilisateur.
     */
    private void extractLocally(Document document, byte[] content) {
        DocxExtraction extraction = docxTextExtractor.extract(content);
        document.setExtractedText(extraction.text());
        document.setStatus(DocumentStatus.EXTRACTED);
        // Ni contenu, ni nom de fichier : seuls le type et un compte d'images non lues.
        log.info("Document Word extrait sur la machine ({} image(s) non lue(s))", extraction.ignoredImages());
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
