package fr.claudegateway.images;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.storage.WorkspaceStorage;

/**
 * <b>Le contenu des images générées dans le stockage objet</b> (F-142 / SF-142-04).
 *
 * <p>Réemploi du stockage existant — S3 en cluster, mémoire en dev et en test —, sous un préfixe à lui,
 * comme les pages (F-109) et les présentations (F-129). Aucun bucket, aucun droit nouveau à déployer.</p>
 *
 * <p><b>L'isolation est dans la clé</b> : {@code generated-images/{userId}/{imageId}/image.png} est
 * toujours reconstruite depuis un utilisateur et une image déjà vérifiés par le service.</p>
 */
@Component
public class GeneratedImageStore {

    static final String PREFIX = "generated-images/";

    private static final String IMAGE = "image.png";

    /** Le content-type d'une image générée (PNG). */
    public static final String CONTENT_TYPE = "image/png";

    private final WorkspaceStorage storage;

    public GeneratedImageStore(WorkspaceStorage storage) {
        this.storage = storage;
    }

    /** Écrit (ou remplace) le PNG d'une image. */
    public void putImage(UUID userId, UUID imageId, byte[] content) {
        storage.putFile(imageKey(userId, imageId), content, CONTENT_TYPE);
    }

    /** Le PNG d'une image, ou vide. */
    public Optional<byte[]> image(UUID userId, UUID imageId) {
        return storage.getFile(imageKey(userId, imageId));
    }

    /** Efface tout le contenu objet d'une image. */
    public void deleteImage(UUID userId, UUID imageId) {
        storage.deletePrefix(PREFIX + userId + "/" + imageId + "/");
    }

    /** La clé du PNG. */
    static String imageKey(UUID userId, UUID imageId) {
        return PREFIX + userId + "/" + imageId + "/" + IMAGE;
    }
}
