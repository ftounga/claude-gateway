package fr.claudegateway.upload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ProviderFileReference;
import fr.claudegateway.ai.ProviderFileUpload;
import fr.claudegateway.docx.DocxExtraction;
import fr.claudegateway.docx.DocxTextExtractor;
import fr.claudegateway.ocr.OcrProperties;

/**
 * Cœur de l'upload F-04 : valide la requête (présence, type MIME, taille), <b>transmet</b> le
 * fichier au fournisseur via l'interface {@link AIProvider} (jamais Anthropic en direct), puis
 * persiste uniquement les <b>métadonnées</b> ({@link UploadedFile}) portant le {@code user_id}
 * courant. Aucun OCR, aucune indexation, aucun stockage du contenu binaire (PROJECT.md §11.6).
 *
 * <p><b>Une exception de forme, pas de principe</b> (F-86 / SF-86-03) : un {@code .docx} est
 * converti en texte <i>avant</i> d'être transmis, parce que le fournisseur ne sait pas lire un
 * {@code .docx}. Relayer ce que le fournisseur sait lire est le métier d'une gateway ; rien n'est
 * indexé ni persisté pour autant, et le texte n'existe que le temps de l'appel. Voir
 * {@code toTransmit}.
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    /** Les types qui ne disent rien : seuls ceux-là déclenchent un examen du contenu (SF-86-02). */
    private static final Set<String> UNDECLARED_TYPES =
            Set.of("application/octet-stream", "application/x-zip-compressed");

    private final AIProvider aiProvider;
    private final UploadedFileRepository uploadedFileRepository;
    private final DocxTextExtractor docxTextExtractor;
    private final UploadProperties properties;

    public UploadService(
            AIProvider aiProvider,
            UploadedFileRepository uploadedFileRepository,
            DocxTextExtractor docxTextExtractor,
            UploadProperties properties) {
        this.aiProvider = aiProvider;
        this.uploadedFileRepository = uploadedFileRepository;
        this.docxTextExtractor = docxTextExtractor;
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
            log.info("Fichier refusé : taille au-dessus du plafond");
            throw new FileTooLargeException("Fichier trop volumineux.");
        }

        String mediaType = normalizeMediaType(file.getContentType());
        byte[] content = null;
        if (!properties.allowedTypeSet().contains(mediaType) && isUndeclared(mediaType)) {
            // Même règle qu'en SF-86-02 : un type qui ne dit rien fait examiner le contenu, un type
            // déclaré et faux n'ouvre aucune porte, un type déclaré et accepté n'est jamais examiné.
            content = readContent(file);
            if (docxTextExtractor.looksLikeDocx(content)) {
                log.info("Fichier déclaré « {} » reconnu comme document Word sur son contenu", mediaType);
                mediaType = OcrProperties.DOCX_MEDIA_TYPE;
            }
        }
        if (!properties.allowedTypeSet().contains(mediaType)) {
            // Le TYPE refusé et la liste blanche sont journalisés, comme sur le chemin voisin
            // (DocumentService) : « type hors liste blanche » sans dire lequel a coûté un
            // aller-retour avec le PO pendant un incident en production — le diagnostic a dû
            // passer par l'utilisateur. Le type n'est pas une donnée sensible : c'est déjà ce que
            // le message d'erreur rend à l'appelant. Le nom du fichier, lui, reste dehors.
            log.info("Fichier refusé : type « {} » hors liste blanche {}",
                    mediaType, properties.allowedTypeSet());
            throw new UnsupportedFileTypeException("Type de fichier non supporté : " + mediaType);
        }

        String filename = StringUtils.cleanPath(
                StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "fichier");

        if (content == null) {
            content = readContent(file);
        }

        // Ce qui part chez le fournisseur : le fichier tel quel, sauf pour Word — voir toTransmit.
        ProviderFileUpload transmitted = toTransmit(filename, mediaType, content);

        // Transmission au fournisseur via l'interface neutre (jamais Anthropic en direct).
        ProviderFileReference reference = aiProvider.uploadFile(transmitted);

        return uploadedFileRepository.save(UploadedFile.builder()
                .userId(userId)
                .providerFileId(reference.providerFileId())
                .filename(filename)
                .mediaType(mediaType)
                .sizeBytes(size)
                .build());
    }

    /** Vrai si le type déclaré ne dit rien, et qu'un document Word serait accepté s'il en était un. */
    private boolean isUndeclared(String mediaType) {
        return UNDECLARED_TYPES.contains(mediaType)
                && properties.allowedTypeSet().contains(OcrProperties.DOCX_MEDIA_TYPE);
    }

    /**
     * Ce qui part réellement chez le fournisseur (F-86 / SF-86-03).
     *
     * <p>Tous les types passent <b>tels quels</b> — sauf Word. <b>Le fournisseur ne lit pas les
     * {@code .docx}</b> : un bloc {@code document} prend du PDF ou du texte, un bloc {@code image}
     * une image. Laisser partir le zip ferait échouer le tour <i>après</i> que l'écran a accepté le
     * fichier : un refus déplacé plus loin et rendu plus obscur, soit exactement le contraire de ce
     * que F-86 vient corriger.
     *
     * <p>La gateway relaie donc ce que le fournisseur sait lire : le texte. Ce n'est pas
     * réimplémenter une capacité du modèle — lire un zip n'en est pas une — et F-04 n'est pas
     * dénaturée : rien n'est indexé, rien du contenu n'est persisté, le texte n'existe que le temps
     * de l'appel, exactement comme les octets d'un PDF aujourd'hui.
     *
     * <p>La copie transmise est nommée {@code <nom d'origine>.txt} : le nom dit les deux, ce que
     * l'utilisateur a joint et ce que le fournisseur détient.
     *
     * @throws fr.claudegateway.docx.InvalidDocxException si le Word est illisible — levée
     *                                                    <b>avant</b> tout appel au fournisseur
     */
    private ProviderFileUpload toTransmit(String filename, String mediaType, byte[] content) {
        if (!OcrProperties.DOCX_MEDIA_TYPE.equals(mediaType)) {
            return new ProviderFileUpload(filename, mediaType, content);
        }
        DocxExtraction extraction = docxTextExtractor.extract(content);
        // Ni contenu ni nom de fichier journalisés : seul le compte d'images non lues.
        log.info("Pièce jointe Word convertie en texte avant transmission ({} image(s) non lue(s))",
                extraction.ignoredImages());
        return new ProviderFileUpload(
                filename + ".txt",
                MediaType.TEXT_PLAIN_VALUE,
                extraction.text().getBytes(StandardCharsets.UTF_8));
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
