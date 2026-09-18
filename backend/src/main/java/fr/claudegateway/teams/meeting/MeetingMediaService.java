package fr.claudegateway.teams.meeting;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.storage.WorkspaceStorage;

/**
 * <b>Le média d'une réunion</b> (F-128 / SF-128-02) : le dépôt de l'audio capturé (onglet Teams + micro,
 * mixé), rattaché à l'artefact réunion. <b>Gateway-First</b> : la gateway <b>stocke</b> ce que le runner
 * a produit sur la machine, elle ne capture pas.
 *
 * <h2>L'isolation est dans la clé et dans la résolution</h2>
 * <p>L'audio vit sous {@code teams-meetings/{userId}/{hostId}/{meetingId}/audio.{ext}} ; la clé est
 * reconstruite à partir de l'utilisateur authentifié et du poste résolu (jamais un chemin venu du client).
 * La réunion est résolue par {@code (id, userId, hostId)} : une réunion d'un autre couple est introuvable.</p>
 */
@Service
public class MeetingMediaService {

    static final String PREFIX = "teams-meetings/";

    /** Types audio acceptés → extension. Liste close ; défaut webm (Opus, natif MediaRecorder). */
    private static final Map<String, String> EXTENSIONS = Map.of(
            "audio/webm", "webm",
            "audio/ogg", "ogg",
            "audio/mp4", "m4a",
            "audio/mpeg", "mp3",
            "audio/wav", "wav",
            "audio/x-wav", "wav");

    /** Types image acceptés → extension (F-128 / SF-128-03). Liste close ; défaut jpg. */
    private static final Map<String, String> IMAGE_EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private final MeetingRepository meetings;
    private final WorkspaceStorage storage;

    public MeetingMediaService(MeetingRepository meetings, WorkspaceStorage storage) {
        this.meetings = meetings;
        this.storage = storage;
    }

    /**
     * Dépose l'audio d'une réunion et met à jour l'artefact ({@code audio_key}, {@code audio_bytes}).
     *
     * @param userId      propriétaire (issu du jeton runner, jamais du corps)
     * @param hostId      poste résolu depuis le terminal Teams possédé
     * @param meetingId   la réunion (résolue par le triplet — sinon {@link MeetingNotFoundException})
     * @param contentType type audio, parmi la liste close (défaut webm si inconnu/absent)
     * @param content     octets de l'audio
     * @return la réunion mise à jour
     */
    public Meeting storeAudio(UUID userId, UUID hostId, UUID meetingId, String contentType, byte[] content) {
        Meeting meeting = meetings.findByIdAndUserIdAndHostId(meetingId, userId, hostId)
                .orElseThrow(() -> new MeetingNotFoundException("Réunion introuvable : " + meetingId));
        String extension = EXTENSIONS.getOrDefault(baseType(contentType), "webm");
        String key = prefixOf(userId, hostId, meetingId) + "audio." + extension;
        storage.putFile(key, content, baseTypeOrDefault(contentType));
        meeting.setAudioKey(key);
        meeting.setAudioBytes((long) content.length);
        return meetings.save(meeting);
    }

    /**
     * Dépose une image clé du partage d'écran (F-128 / SF-128-03) sous {@code …/{meetingId}/frames/} et
     * met à jour {@code image_count} = nombre d'images retenues. Isolation identique à l'audio.
     */
    public Meeting storeImage(UUID userId, UUID hostId, UUID meetingId, String contentType, byte[] content) {
        Meeting meeting = meetings.findByIdAndUserIdAndHostId(meetingId, userId, hostId)
                .orElseThrow(() -> new MeetingNotFoundException("Réunion introuvable : " + meetingId));
        String extension = IMAGE_EXTENSIONS.getOrDefault(baseType(contentType), "jpg");
        String imageId = UUID.randomUUID().toString().replace("-", "");
        storage.putFile(framesPrefixOf(userId, hostId, meetingId) + imageId + "." + extension,
                content, baseType(contentType).isEmpty() ? "image/jpeg" : baseType(contentType));
        meeting.setImageCount(countImages(userId, hostId, meetingId));
        return meetings.save(meeting);
    }

    /** Le nombre d'images clés déjà remontées pour cette réunion (sert au plafond et au compteur). */
    public int countImages(UUID userId, UUID hostId, UUID meetingId) {
        return storage.listKeys(framesPrefixOf(userId, hostId, meetingId)).size();
    }

    static String prefixOf(UUID userId, UUID hostId, UUID meetingId) {
        return PREFIX + userId + "/" + hostId + "/" + meetingId + "/";
    }

    static String framesPrefixOf(UUID userId, UUID hostId, UUID meetingId) {
        return prefixOf(userId, hostId, meetingId) + "frames/";
    }

    private static String baseType(String contentType) {
        String value = contentType == null ? "" : contentType.strip().toLowerCase(Locale.ROOT);
        int separator = value.indexOf(';');
        return separator < 0 ? value : value.substring(0, separator).strip();
    }

    private static String baseTypeOrDefault(String contentType) {
        String base = baseType(contentType);
        return base.isEmpty() ? "audio/webm" : base;
    }
}
