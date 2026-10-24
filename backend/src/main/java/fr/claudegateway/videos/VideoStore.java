package fr.claudegateway.videos;

import java.io.InputStream;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.storage.WorkspaceStorage;

/**
 * <b>Où vivent les octets des vidéos</b> (F-213 / SF-213-01) : {@code videos/{userId}/{videoId}/v{n}/}, dans le
 * stockage existant (S3 en cluster, mémoire en dev et en test). Envoi par morceaux, lecture par plage : une
 * vidéo de 300 Mo ne passe jamais entière en mémoire.
 */
@Component
public class VideoStore {

    static final String PREFIX = "videos/";

    private final WorkspaceStorage storage;

    public VideoStore(WorkspaceStorage storage) {
        this.storage = storage;
    }

    /** La clé du média d'une version. */
    public static String mediaKey(UUID userId, UUID videoId, int version, VideoMediaTypes.MediaType type) {
        return versionPrefix(userId, videoId, version) + type.fileName();
    }

    /** La clé de la couverture d'une version. */
    public static String posterKey(UUID userId, UUID videoId, int version, String posterMime) {
        return versionPrefix(userId, videoId, version) + "poster." + VideoMediaTypes.posterExtension(posterMime);
    }

    /** Ouvre l'envoi par morceaux d'un objet. */
    public WorkspaceStorage.Upload openUpload(String key, String mime) {
        return storage.openUpload(key, mime);
    }

    /** Range une petite pièce (couverture). */
    public void put(String key, byte[] content, String mime) {
        storage.putFile(key, content, mime);
    }

    /** La taille d'un objet, ou vide. */
    public OptionalLong size(String key) {
        return storage.size(key);
    }

    /** Une plage d'un objet, en flux. */
    public Optional<InputStream> openRange(String key, long start, long endInclusive) {
        return storage.openRange(key, start, endInclusive);
    }

    /** Un petit objet entier (couverture). */
    public Optional<byte[]> get(String key) {
        return storage.getFile(key);
    }

    /** Efface un objet (sans effet s'il est absent). */
    public void delete(String key) {
        if (key != null) {
            storage.deleteFile(key);
        }
    }

    /** Efface une version entière. */
    public void deleteVersion(UUID userId, UUID videoId, int version) {
        storage.deletePrefix(versionPrefix(userId, videoId, version));
    }

    /** Efface une vidéo entière. */
    public void deleteVideo(UUID userId, UUID videoId) {
        storage.deletePrefix(PREFIX + userId + "/" + videoId + "/");
    }

    /** Efface toutes les vidéos d'un compte. */
    public void deleteAccount(UUID userId) {
        storage.deletePrefix(PREFIX + userId + "/");
    }

    static String versionPrefix(UUID userId, UUID videoId, int version) {
        return PREFIX + userId + "/" + videoId + "/v" + version + "/";
    }
}
