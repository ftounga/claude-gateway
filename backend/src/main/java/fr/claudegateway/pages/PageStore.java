package fr.claudegateway.pages;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.storage.WorkspaceStorage;

/**
 * <b>Le contenu des pages dans le stockage objet</b> (F-109 / SF-109-01, décision D3).
 *
 * <p>Réemploi du stockage existant — S3 en cluster ({@code S3WorkspaceStorage}), mémoire en dev et en
 * test —, sous un préfixe à lui, comme les images de moments (F-89). Aucun bucket, aucun droit nouveau
 * à déployer.</p>
 *
 * <p><b>L'isolation est dans la clé</b> : {@code pages/{userId}/{pageId}/v{N}/…} est toujours
 * <b>reconstruite</b> depuis un utilisateur et une page déjà vérifiés par le service ; un nom de pièce
 * jointe n'est jamais qu'un dernier segment validé par {@link PageAttachments}.</p>
 */
@Component
public class PageStore {

    static final String PREFIX = "pages/";

    private static final String INDEX = "index.html";

    private final WorkspaceStorage storage;

    public PageStore(WorkspaceStorage storage) {
        this.storage = storage;
    }

    /** Écrit une version : le HTML, puis chaque pièce jointe. */
    public void putVersion(UUID userId, UUID pageId, int version, byte[] html, Map<String, byte[]> attachments) {
        storage.putFile(versionPrefix(userId, pageId, version) + INDEX, html, "text/html; charset=utf-8");
        attachments.forEach((name, content) -> storage.putFile(
                versionPrefix(userId, pageId, version) + "files/" + name, content,
                PageAttachments.contentType(name).orElse("application/octet-stream")));
    }

    /** Le HTML d'une version, ou vide. */
    public Optional<byte[]> html(UUID userId, UUID pageId, int version) {
        return storage.getFile(versionPrefix(userId, pageId, version) + INDEX);
    }

    /** Une pièce jointe d'une version, ou vide — y compris pour un nom invalide. */
    public Optional<byte[]> attachment(UUID userId, UUID pageId, int version, String name) {
        if (!PageAttachments.isValidName(name)) {
            return Optional.empty();
        }
        return storage.getFile(versionPrefix(userId, pageId, version) + "files/" + name);
    }

    /** Efface une version (HTML et pièces jointes). */
    public void deleteVersion(UUID userId, UUID pageId, int version) {
        storage.deletePrefix(versionPrefix(userId, pageId, version));
    }

    /** Efface toutes les versions d'une page. */
    public void deletePage(UUID userId, UUID pageId) {
        storage.deletePrefix(PREFIX + userId + "/" + pageId + "/");
    }

    /** La barre finale compte : sans elle, {@code v1} engloberait {@code v10}. */
    static String versionPrefix(UUID userId, UUID pageId, int version) {
        return PREFIX + userId + "/" + pageId + "/v" + version + "/";
    }
}
