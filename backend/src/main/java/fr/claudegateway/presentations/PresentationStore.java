package fr.claudegateway.presentations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.storage.WorkspaceStorage;

/**
 * <b>Le contenu des présentations dans le stockage objet</b> (F-129 / SF-129-02).
 *
 * <p>Réemploi du stockage existant — S3 en cluster, mémoire en dev et en test —, sous un préfixe à lui,
 * comme les pages (F-109) et les médias de réunion (F-128). Aucun bucket, aucun droit nouveau à
 * déployer.</p>
 *
 * <p><b>L'isolation est dans la clé</b> : {@code presentations/{userId}/{presentationId}/…} est toujours
 * reconstruite depuis un utilisateur et une présentation déjà vérifiés par le service.</p>
 */
@Component
public class PresentationStore {

    static final String PREFIX = "presentations/";

    private static final String PPTX = "presentation.pptx";

    /** Le sous-dossier des images de slides (F-129 / SF-129-03). */
    static final String SLIDES = "slides/";

    /** Le content-type OpenXML d'un .pptx. */
    public static final String PPTX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    private final WorkspaceStorage storage;

    public PresentationStore(WorkspaceStorage storage) {
        this.storage = storage;
    }

    /** Écrit (ou remplace) le fichier {@code .pptx} d'une présentation. */
    public void putPptx(UUID userId, UUID presentationId, byte[] content) {
        storage.putFile(pptxKey(userId, presentationId), content, PPTX_CONTENT_TYPE);
    }

    /** Le {@code .pptx} d'une présentation, ou vide. */
    public Optional<byte[]> pptx(UUID userId, UUID presentationId) {
        return storage.getFile(pptxKey(userId, presentationId));
    }

    /** Efface tout le contenu d'une présentation (le {@code .pptx} et les slides). */
    public void deletePresentation(UUID userId, UUID presentationId) {
        storage.deletePrefix(PREFIX + userId + "/" + presentationId + "/");
    }

    // ---------------------------------------------------------------- slides (SF-129-03)

    /** Écrit une image de slide (F-129 / SF-129-03), numérotée à partir de 1. */
    public void putSlide(UUID userId, UUID presentationId, int index, byte[] content, String contentType) {
        storage.putFile(slideKey(userId, presentationId, index), content, contentType);
    }

    /** Une image de slide (1-based), ou vide. */
    public Optional<byte[]> slide(UUID userId, UUID presentationId, int index) {
        return storage.getFile(slideKey(userId, presentationId, index));
    }

    /** Efface les images de slides d'une présentation (avant un nouveau rendu). */
    public void deleteSlides(UUID userId, UUID presentationId) {
        storage.deletePrefix(PREFIX + userId + "/" + presentationId + "/" + SLIDES);
    }

    /** Le nombre d'images de slides présentes dans le stockage pour cette présentation. */
    public int countSlides(UUID userId, UUID presentationId) {
        return storage.listKeys(PREFIX + userId + "/" + presentationId + "/" + SLIDES).size();
    }

    /** La clé du {@code .pptx}. */
    static String pptxKey(UUID userId, UUID presentationId) {
        return PREFIX + userId + "/" + presentationId + "/" + PPTX;
    }

    /** La clé d'une image de slide (1-based), zéro-remplie pour un tri lexicographique stable. */
    static String slideKey(UUID userId, UUID presentationId, int index) {
        return PREFIX + userId + "/" + presentationId + "/" + SLIDES
                + String.format("%04d", index) + ".png";
    }

    /** Les clés d'images de slides existantes, triées. Utilitaire de test/inspection. */
    List<String> slideKeys(UUID userId, UUID presentationId) {
        return storage.listKeys(PREFIX + userId + "/" + presentationId + "/" + SLIDES).stream().sorted()
                .toList();
    }
}
