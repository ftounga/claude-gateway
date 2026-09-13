package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <b>La reprise</b> (F-90 / SF-90-03) : l'état d'un travail, écrit sur le disque de la machine et
 * relu.
 *
 * <h2>L'identifiant est dérivé du fichier, pas tiré au hasard</h2>
 *
 * <p>Un identifiant tiré au hasard obligerait l'agent à le retenir d'un tour sur l'autre — et un
 * agent qui a changé de tour ne l'a plus. Le dériver du <b>chemin, de la taille et de la date de
 * modification</b> de l'enregistrement fait qu'une reprise <b>retrouve son travail toute seule</b> :
 * redemander le même enregistrement tombe sur le même identifiant. Et un fichier modifié —
 * réenregistré, remplacé — en démarre un <b>autre</b>, ce qui est exactement ce qu'on veut : ce
 * n'est plus la même réunion.</p>
 *
 * <h2>Jamais un fichier d'état à moitié écrit</h2>
 *
 * <p>L'écriture passe par un fichier temporaire déplacé d'un bloc. Un état tronqué relu plus tard
 * ferait croire à un travail dans un état qu'il n'a jamais eu.</p>
 */
public final class MomentsJobStore {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TeamsWorkFolder folder;

    public MomentsJobStore(TeamsWorkFolder folder) {
        this.folder = folder;
    }

    /**
     * L'identifiant du travail de cet enregistrement.
     *
     * @return 16 caractères hexadécimaux, stables pour un fichier inchangé
     */
    public static String idFor(Path video) {
        String material = video == null ? "" : video.toAbsolutePath().normalize().toString();
        long size = 0L;
        long modified = 0L;
        try {
            if (video != null && Files.isRegularFile(video)) {
                size = Files.size(video);
                modified = Files.getLastModifiedTime(video).toMillis();
            }
        } catch (IOException e) {
            // Un fichier qu'on ne peut pas mesurer donne un identifiant fondé sur son seul chemin :
            // moins fin, mais stable — et le travail échouera plus loin, en le disant.
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(material.getBytes(StandardCharsets.UTF_8));
            digest.update(Long.toString(size).getBytes(StandardCharsets.UTF_8));
            digest.update(Long.toString(modified).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 absent de cette JVM", e);
        }
    }

    /** Le travail de cet identifiant, s'il a déjà été écrit. */
    public Optional<MomentsJob> find(String id) {
        Path file = fileOf(id);
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MomentsJob.fromJson(mapper.readTree(Files.readString(file,
                    StandardCharsets.UTF_8))));
        } catch (IOException | RuntimeException e) {
            // Un état illisible n'est pas un état : on repart de zéro plutôt que de faire semblant.
            return Optional.empty();
        }
    }

    /** Écrit l'état, d'un bloc. */
    public void save(MomentsJob job) {
        Path file = fileOf(job.id());
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path staging = Files.createTempFile(file.getParent(), "job-", ".tmp");
            Files.writeString(staging, job.toJson(mapper).toString(), StandardCharsets.UTF_8);
            Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Ne pas pouvoir écrire l'état n'annule pas le travail en cours : il perdra seulement sa
            // reprise. On ne fait pas échouer une extraction d'une heure pour un disque plein.
        }
    }

    /** Efface l'état — sert au redémarrage explicite d'un travail échoué. */
    public void forget(String id) {
        Path file = fileOf(id);
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // Idem : un état résiduel sera écrasé au prochain enregistrement.
        }
    }

    /** Le dossier de travail des images de ce travail. */
    public Path framesDirOf(String id) throws IOException {
        return folder.ensure(folder.root().resolve("frames").resolve(TeamsWorkFolder.safe(id)));
    }

    private Path fileOf(String id) {
        String safe = TeamsWorkFolder.safe(id);
        if (safe.isEmpty() || "sans-nom".equals(safe)) {
            return null;
        }
        return folder.root().resolve("jobs").resolve(safe + ".json");
    }
}
