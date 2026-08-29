package fr.claudegateway.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Persistance locale du jeton runner (F-38 / SF-38-03) pour éviter de réappairer à chaque lancement.
 *
 * <p>Emplacement : {@code <workspace>/.claude-runner/token.json} si le workspace est inscriptible,
 * repli sur {@code ~/.claude-runner/token.json} sinon. Le fichier reçoit des permissions
 * restreintes ({@code rw-------}) sur les systèmes POSIX. {@link #load()} renvoie vide si le fichier
 * est absent, illisible, corrompu ou expiré : dans tous ces cas, un réappairage sera tenté si un
 * code est fourni.</p>
 */
public final class TokenStore {

    private static final String DIR_NAME = ".claude-runner";
    private static final String FILE_NAME = "token.json";

    private final Path tokenFile;
    private final ObjectMapper mapper;

    public TokenStore(Path workspaceRoot, Path homeDir) {
        this.tokenFile = resolveTokenFile(workspaceRoot, homeDir);
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.mapper.configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    /** Emplacement retenu pour le fichier de jeton (utile aux logs et aux tests). */
    public Path tokenFile() {
        return tokenFile;
    }

    /** Charge le jeton stocké ; vide si absent, illisible, corrompu ou expiré. */
    public Optional<StoredToken> load() {
        if (!Files.isReadable(tokenFile)) {
            return Optional.empty();
        }
        try {
            StoredToken token = mapper.readValue(tokenFile.toFile(), StoredToken.class);
            if (token == null || token.token() == null || token.token().isBlank()) {
                return Optional.empty();
            }
            if (token.isExpired(OffsetDateTime.now())) {
                return Optional.empty();
            }
            return Optional.of(token);
        } catch (IOException e) {
            // Fichier corrompu : traité comme absent (réappairage possible).
            return Optional.empty();
        }
    }

    /** Écrit le jeton sur disque, en créant le dossier et en restreignant les permissions. */
    public void save(StoredToken token) {
        try {
            Path dir = tokenFile.getParent();
            Files.createDirectories(dir);
            restrictDir(dir);
            mapper.writerWithDefaultPrettyPrinter().writeValue(tokenFile.toFile(), token);
            restrictFile(tokenFile);
        } catch (IOException e) {
            throw new RunnerException("Impossible d'écrire le jeton runner : " + tokenFile, e);
        }
    }

    /** Supprime le jeton stocké (ex : rejeté par la gateway). Idempotent. */
    public void clear() {
        try {
            Files.deleteIfExists(tokenFile);
        } catch (IOException e) {
            // Best-effort : on ne bloque pas l'arrêt sur un échec de suppression.
        }
    }

    private static Path resolveTokenFile(Path workspaceRoot, Path homeDir) {
        Path preferred = workspaceRoot.resolve(DIR_NAME).resolve(FILE_NAME);
        if (Files.isWritable(workspaceRoot)) {
            return preferred;
        }
        return homeDir.resolve(DIR_NAME).resolve(FILE_NAME);
    }

    private static void restrictFile(Path file) {
        Set<PosixFilePermission> perms = EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        trySetPerms(file, perms);
    }

    private static void restrictDir(Path dir) {
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwx------");
        trySetPerms(dir, perms);
    }

    private static void trySetPerms(Path path, Set<PosixFilePermission> perms) {
        try {
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException e) {
            // Systèmes non-POSIX (Windows) : les ACL héritées s'appliquent, pas d'échec dur.
        }
    }
}
