package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <b>La mémoire des captures</b> (F-91 / SF-91-01) : l'état d'une capture, écrit sur le disque de la
 * machine et relu.
 *
 * <h2>Un identifiant tiré au hasard, contrairement aux moments</h2>
 *
 * <p>{@code MomentsJobStore} dérive son identifiant du fichier vidéo, parce que le fichier
 * <b>existe déjà</b> quand le travail commence. Ici, il n'existe pas encore : il n'y a rien dont
 * dériver. L'identifiant est donc tiré au hasard — et le problème que cela poserait (« l'agent
 * l'oublie d'un tour sur l'autre ») est résolu autrement : <b>il n'y a qu'une capture en cours à la
 * fois</b>, et on la retrouve sans identifiant.</p>
 *
 * <h2>Jamais un état à moitié écrit</h2>
 *
 * <p>L'écriture passe par un fichier temporaire déplacé d'un bloc. Un état tronqué relu plus tard
 * ferait croire à une capture dans un état qu'elle n'a jamais eu — et, s'agissant du seul document
 * qui dise qu'un enregistrement existe, ce serait la pire des pertes.</p>
 */
public final class CaptureStore {

    /** Au-delà, on n'apprend plus rien de l'historique : les plus anciennes sont oubliées. */
    static final int MAX_KEPT = 50;

    private final ObjectMapper mapper = new ObjectMapper();
    private final TeamsWorkFolder folder;

    public CaptureStore(TeamsWorkFolder folder) {
        this.folder = folder;
    }

    /** Le dossier où vit la vidéo de cette capture. Fixe, jamais composé depuis un appel d'outil. */
    public Path captureDir(String id) throws IOException {
        return folder.ensure(folder.capturesDir().resolve(TeamsWorkFolder.safe(id)));
    }

    /** La capture de cet identifiant, si elle a déjà été écrite. */
    public Optional<CaptureRecord> find(String id) {
        Path file = fileOf(id);
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(CaptureRecord.fromJson(
                    mapper.readTree(Files.readString(file, StandardCharsets.UTF_8))));
        } catch (IOException | RuntimeException e) {
            // Un état illisible n'est pas un état : on le dit absent plutôt que de faire semblant.
            return Optional.empty();
        }
    }

    /** Toutes les captures connues, de la plus récente à la plus ancienne. */
    public List<CaptureRecord> all() {
        Path dir = folder.capturesDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<CaptureRecord> found = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path entry : stream.toList()) {
                Path state = entry.resolve("capture.json");
                if (!Files.isRegularFile(state)) {
                    continue;
                }
                try {
                    found.add(CaptureRecord.fromJson(
                            mapper.readTree(Files.readString(state, StandardCharsets.UTF_8))));
                } catch (IOException | RuntimeException ignored) {
                    // Une capture illisible n'empêche pas de lire les autres.
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        found.sort(Comparator.comparing(
                (CaptureRecord record) -> record.startedAt() == null
                        ? java.time.Instant.EPOCH : record.startedAt()).reversed());
        return found.size() <= MAX_KEPT ? List.copyOf(found)
                : List.copyOf(found.subList(0, MAX_KEPT));
    }

    /**
     * La capture <b>en cours</b>, s'il y en a une. Il ne peut y en avoir qu'une : c'est ce qui permet
     * de l'arrêter sans avoir à se souvenir de son identifiant.
     */
    public Optional<CaptureRecord> running() {
        return all().stream().filter(record -> !record.isOver()).findFirst();
    }

    /** Écrit l'état, d'un bloc. */
    public void save(CaptureRecord record) {
        Path file = fileOf(record.id());
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path staging = Files.createTempFile(file.getParent(), "capture-", ".tmp");
            Files.writeString(staging, record.toJson(mapper).toString(), StandardCharsets.UTF_8);
            Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Ne pas pouvoir écrire l'état n'arrête pas une capture en cours : elle perdrait
            // seulement sa mémoire. On n'interrompt pas un enregistrement de réunion pour un
            // disque plein.
        }
    }

    private Path fileOf(String id) {
        String safe = TeamsWorkFolder.safe(id);
        if (safe.isEmpty() || "sans-nom".equals(safe)) {
            return null;
        }
        return folder.capturesDir().resolve(safe).resolve("capture.json");
    }
}
