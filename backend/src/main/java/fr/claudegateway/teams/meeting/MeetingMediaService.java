package fr.claudegateway.teams.meeting;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    /** Extension → type audio canonique (F-128 / SF-128-10) : redonne le bon Content-Type à la lecture. */
    private static final Map<String, String> AUDIO_TYPE_BY_EXT = Map.of(
            "webm", "audio/webm",
            "ogg", "audio/ogg",
            "m4a", "audio/mp4",
            "mp3", "audio/mpeg",
            "wav", "audio/wav");

    /** Extension → type image canonique (F-128 / SF-128-10). */
    private static final Map<String, String> IMAGE_TYPE_BY_EXT = Map.of(
            "jpg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp");

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

    /**
     * Purge du stockage objet les <b>médias lourds</b> d'une réunion (audio + images du deck), au-delà de
     * la rétention (F-128 / SF-128-07). Efface tout ce qui vit sous
     * {@code teams-meetings/{userId}/{hostId}/{meetingId}/} — {@code audio.*} <b>et</b> {@code frames/*} —
     * en un seul geste borné/paginé côté stockage. La clé porte {@code user_id}+{@code host_id} : une
     * réunion d'un autre couple n'est jamais touchée.
     *
     * <p>N'écrit rien en base : la mise à vide des pointeurs et l'horodatage {@code media_purged_at}
     * relèvent de {@code MeetingRetentionService}. Un effacement <b>incomplet</b> lève
     * {@code WorkspaceStorageDeletionException} (l'appelant ne marque alors pas la réunion purgée).</p>
     */
    public void deleteMedia(UUID userId, UUID hostId, UUID meetingId) {
        storage.deletePrefix(prefixOf(userId, hostId, meetingId));
    }

    // ----------------------------------------------------------------- lecture (F-128 / SF-128-10)

    /**
     * L'audio d'une réunion, si elle existe <b>pour ce couple {@code user_id}/{@code host_id}</b> et
     * porte un audio. La réunion est résolue par le triplet (isolation), puis les octets sont relus à
     * la clé enregistrée. Le {@code Content-Type} est redéduit de l'extension de la clé, jamais d'un
     * paramètre client.
     *
     * @return le type et les octets, ou vide — <b>vide</b> couvre « inconnue », « à quelqu'un d'autre »
     *         et « sans audio », qui doivent rester indiscernables (404 unique côté controller)
     */
    public Optional<StoredMedia> findAudio(UUID userId, UUID hostId, UUID meetingId) {
        Meeting meeting = meetings.findByIdAndUserIdAndHostId(meetingId, userId, hostId).orElse(null);
        if (meeting == null || meeting.getAudioKey() == null) {
            return Optional.empty();
        }
        String contentType = AUDIO_TYPE_BY_EXT.getOrDefault(extensionOf(meeting.getAudioKey()), "audio/webm");
        return storage.getFile(meeting.getAudioKey())
                .map(bytes -> new StoredMedia(contentType, bytes));
    }

    /**
     * Les identifiants des images clés d'une réunion (deck reconstitué), ordre stable. La clé porte
     * déjà {@code user_id}/{@code host_id}/{@code meeting_id} : une réunion d'un autre couple ne rend
     * aucune image. Vide si aucune image (ou réunion inconnue) — indiscernable, comme les moments F-89.
     */
    public List<String> listFrames(UUID userId, UUID hostId, UUID meetingId) {
        String prefix = framesPrefixOf(userId, hostId, meetingId);
        return storage.listKeys(prefix).stream()
                .map(key -> key.substring(prefix.length()))
                .map(name -> name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name)
                .sorted()
                .toList();
    }

    /**
     * Une image clé d'une réunion, si elle existe pour ce couple. L'identifiant est un <b>dernier
     * segment</b> (lettres/chiffres/{@code -}/{@code _}), jamais un chemin : un {@code ../} est rejeté.
     */
    public Optional<StoredMedia> findFrame(UUID userId, UUID hostId, UUID meetingId, String imageId) {
        if (!isSafeId(imageId)) {
            return Optional.empty();
        }
        String base = framesPrefixOf(userId, hostId, meetingId) + imageId + ".";
        for (Map.Entry<String, String> type : IMAGE_TYPE_BY_EXT.entrySet()) {
            Optional<byte[]> content = storage.getFile(base + type.getKey());
            if (content.isPresent()) {
                return Optional.of(new StoredMedia(type.getValue(), content.get()));
            }
        }
        return Optional.empty();
    }

    private static String extensionOf(String key) {
        int dot = key.lastIndexOf('.');
        return dot < 0 ? "" : key.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Un identifiant d'image est un <b>dernier segment</b>, jamais un chemin : lettres, chiffres,
     * {@code -} et {@code _} seulement. Ce qui exclut {@code ..}, {@code /} et toute traversée.
     */
    private static boolean isSafeId(String imageId) {
        if (imageId == null || imageId.isBlank() || imageId.length() > 100) {
            return false;
        }
        return imageId.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_');
    }

    /** Un média de réunion relu : son type, et ses octets. */
    public record StoredMedia(String contentType, byte[] content) {
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
