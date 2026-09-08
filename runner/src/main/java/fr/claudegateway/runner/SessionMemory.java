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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Mémoire de reprise du runner (F-46 / SF-46-01) : ce qu'il faut savoir pour <b>redémarrer</b> une
 * machine déjà appairée, sans un seul argument.
 *
 * <p>Le jeton (SF-38-03) survivait déjà au redémarrage du poste — mais il ne portait que le
 * <b>secret</b>, jamais le contexte qui permet de s'en servir : ni l'adresse de la passerelle, ni la
 * racine du projet. L'utilisateur devait donc retourner dans l'application chercher une commande de
 * trois arguments pour une machine qui n'avait pas bougé.</p>
 *
 * <p>Ce fichier ne contient <b>aucun secret</b> — ni jeton, ni code d'appairage, ni identifiant
 * d'utilisateur (D2). C'est ce qui autorise à en déposer une copie dans
 * {@code ~/.claude-runner/session.json} : la reprise devient possible hors du dossier du projet (le
 * double-clic du lanceur, SF-46-02) sans dupliquer le jeton, qui lui reste là où il a été écrit.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionMemory(
        @JsonProperty("gateway") String gateway,
        @JsonProperty("workspaceRoot") String workspaceRoot,
        @JsonProperty("updatedAt") OffsetDateTime updatedAt) {

    /** Nom du fichier de reprise, dans le {@code .claude-runner} déjà utilisé par le jeton. */
    static final String FILE_NAME = "session.json";

    private static final String DIR_NAME = ".claude-runner";

    @JsonCreator
    public SessionMemory {
    }

    /**
     * Où la mémoire a été trouvée, et ce qu'elle dit. La <b>source</b> compte : une mémoire trouvée
     * dans un projet permet de replier sur le dossier porteur si la racine mémorisée a disparu — un
     * projet déplacé avec son {@code .claude-runner} reste reprenable ; une mémoire trouvée dans
     * {@code ~} ne le permet pas.
     */
    public record Located(SessionMemory memory, Path file, boolean fromWorkspace) {

        /**
         * Racine à retenir : celle qui est mémorisée si elle existe encore, sinon le dossier
         * porteur du {@code .claude-runner} quand la mémoire vient d'un projet. Vide si aucune des
         * deux n'est exploitable — le refus dira alors quoi faire, il ne devinera pas (D3).
         */
        public Optional<Path> resolveRoot() {
            String recorded = memory.workspaceRoot();
            if (recorded != null && !recorded.isBlank()) {
                Path root = Path.of(recorded).toAbsolutePath().normalize();
                if (Files.isDirectory(root)) {
                    return Optional.of(root);
                }
            }
            if (fromWorkspace) {
                Path holder = file.getParent() == null ? null : file.getParent().getParent();
                if (holder != null && Files.isDirectory(holder)) {
                    return Optional.of(holder.toAbsolutePath().normalize());
                }
            }
            return Optional.empty();
        }

        /** Chemin mémorisé tel qu'écrit, pour le citer dans un message de refus. */
        public String recordedRoot() {
            return memory.workspaceRoot();
        }
    }

    /**
     * Cherche la mémoire de reprise : le répertoire courant, puis ses <b>ancêtres</b> — le runner se
     * lance souvent depuis un sous-dossier du projet —, puis le repli {@code ~/.claude-runner}.
     *
     * <p>Un fichier illisible ou corrompu est traité comme <b>absent</b> : c'est le même parti que
     * {@link TokenStore#load()}. Une reprise est un confort ; elle ne doit jamais faire tomber un
     * démarrage qui pourrait aboutir par la ligne de commande.</p>
     */
    public static Optional<Located> locate(Path currentDir, Path homeDir) {
        Path dir = currentDir == null ? null : currentDir.toAbsolutePath().normalize();
        while (dir != null) {
            Path candidate = dir.resolve(DIR_NAME).resolve(FILE_NAME);
            Optional<SessionMemory> read = read(candidate);
            if (read.isPresent()) {
                return Optional.of(new Located(read.get(), candidate, true));
            }
            dir = dir.getParent();
        }
        if (homeDir != null) {
            Path fallback = homeDir.resolve(DIR_NAME).resolve(FILE_NAME);
            Optional<SessionMemory> read = read(fallback);
            if (read.isPresent()) {
                return Optional.of(new Located(read.get(), fallback, false));
            }
        }
        return Optional.empty();
    }

    /**
     * Écrit la mémoire aux deux emplacements — celui du projet, celui du compte — en
     * <b>best-effort</b> : un appairage réussi ne doit pas échouer parce qu'un disque est plein ou
     * un dossier en lecture seule (D4). Rend les fichiers réellement écrits.
     */
    public static java.util.List<Path> remember(SessionMemory memory, Path workspaceRoot, Path homeDir) {
        java.util.List<Path> written = new java.util.ArrayList<>();
        for (Path base : bases(workspaceRoot, homeDir)) {
            Path file = base.resolve(DIR_NAME).resolve(FILE_NAME);
            if (write(memory, file)) {
                written.add(file);
            }
        }
        return written;
    }

    /** Emplacement de la mémoire pour une base donnée (utile aux tests et aux messages). */
    public static Path fileIn(Path base) {
        return base.resolve(DIR_NAME).resolve(FILE_NAME);
    }

    private static java.util.List<Path> bases(Path workspaceRoot, Path homeDir) {
        java.util.List<Path> bases = new java.util.ArrayList<>();
        if (workspaceRoot != null) {
            bases.add(workspaceRoot);
        }
        // Le repli n'est pas une redondance : c'est lui qui rend la reprise possible quand le
        // répertoire courant n'est pas le projet — le cas du lanceur double-cliqué (SF-46-02).
        if (homeDir != null && (workspaceRoot == null
                || !homeDir.toAbsolutePath().normalize().equals(workspaceRoot.toAbsolutePath().normalize()))) {
            bases.add(homeDir);
        }
        return bases;
    }

    private static Optional<SessionMemory> read(Path file) {
        if (!Files.isReadable(file) || Files.isDirectory(file)) {
            return Optional.empty();
        }
        try {
            SessionMemory memory = mapper().readValue(file.toFile(), SessionMemory.class);
            if (memory == null || memory.gateway() == null || memory.gateway().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(memory);
        } catch (IOException | RuntimeException e) {
            // Corrompue : traitée comme absente. Un fichier de confort ne fait pas tomber le runner.
            return Optional.empty();
        }
    }

    private static boolean write(SessionMemory memory, Path file) {
        try {
            Path dir = file.getParent();
            Files.createDirectories(dir);
            trySetPerms(dir, PosixFilePermissions.fromString("rwx------"));
            mapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), memory);
            trySetPerms(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        return mapper;
    }

    private static void trySetPerms(Path path, Set<PosixFilePermission> perms) {
        try {
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException e) {
            // Systèmes non-POSIX (Windows) : les ACL héritées s'appliquent, pas d'échec dur.
        }
    }
}
