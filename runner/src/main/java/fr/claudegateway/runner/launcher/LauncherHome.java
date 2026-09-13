package fr.claudegateway.runner.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import fr.claudegateway.runner.RunnerBuild;

/**
 * Le dossier du lanceur (F-111 / SF-111-02) : {@code ~/.claude-runner/}.
 *
 * <pre>
 * ~/.claude-runner/
 *   versions/&lt;id&gt;/runner.jar          le vrai runner de cette version
 *   versions/&lt;id&gt;/runner.jar.sha256   son empreinte, relue avant chaque démarrage
 *   current-version                     la version que le lanceur démarre
 *   next-version                        écrite par le runner avant de sortir en 75
 * </pre>
 *
 * <p>Un fichier n'est jamais écrit en place : il est écrit à côté puis <b>déplacé</b>, pour qu'un
 * arrêt brutal ne laisse pas un jar à moitié copié que le lanceur démarrerait ensuite.</p>
 */
public final class LauncherHome {

    /** Surcharge du dossier (tests, postes où le dossier personnel est en lecture seule). */
    public static final String HOME_ENV = "CLAUDE_RUNNER_HOME";

    static final String JAR = "runner.jar";
    static final String SHA = "runner.jar.sha256";
    static final String CURRENT = "current-version";
    static final String NEXT = "next-version";
    static final String HEALTH = "connected";
    static final String REPORT = "update-report.json";

    private final Path root;

    public LauncherHome(Path root) {
        this.root = root;
    }

    /** Le dossier de ce poste : {@code CLAUDE_RUNNER_HOME}, sinon {@code ~/.claude-runner}. */
    public static LauncherHome resolve(Map<String, String> env, String userHome) {
        String forced = env == null ? null : env.get(HOME_ENV);
        if (forced != null && !forced.isBlank()) {
            return new LauncherHome(Path.of(forced.trim()));
        }
        return new LauncherHome(Path.of(userHome == null ? "." : userHome, ".claude-runner"));
    }

    public Path root() {
        return root;
    }

    /** Le jar d'une version, qu'il existe ou non. L'identifiant est validé : jamais un chemin. */
    public Path jarOf(String id) {
        return versionDir(id).resolve(JAR);
    }

    /**
     * Vrai si la version est installée <b>et intacte</b> : le jar existe et son empreinte est celle
     * écrite à l'installation. Un jar modifié sur le disque n'est pas démarré.
     */
    public boolean isInstalled(String id) {
        if (RunnerBuild.parseId(id).isEmpty()) {
            return false;
        }
        Path jar = jarOf(id);
        Path sha = versionDir(id).resolve(SHA);
        if (!Files.isRegularFile(jar) || !Files.isRegularFile(sha)) {
            return false;
        }
        try {
            String expected = Files.readString(sha, StandardCharsets.US_ASCII).trim();
            return expected.equalsIgnoreCase(sha256(jar));
        } catch (IOException e) {
            return false;
        }
    }

    /** Copie un jar dans {@code versions/<id>/} (écriture à côté, puis déplacement). */
    public Path install(String id, Path sourceJar) throws IOException {
        try (InputStream in = Files.newInputStream(sourceJar)) {
            return install(id, in.readAllBytes());
        }
    }

    /** Écrit des octets <b>déjà vérifiés</b> comme jar de la version {@code id}. */
    public Path install(String id, byte[] jarBytes) throws IOException {
        Path dir = versionDir(id);
        Files.createDirectories(dir);
        Path jar = dir.resolve(JAR);
        Path tmp = dir.resolve(JAR + ".tmp");
        Files.write(tmp, jarBytes);
        move(tmp, jar);
        writeAtomically(dir.resolve(SHA), sha256(jarBytes));
        return jar;
    }

    public Optional<String> currentVersion() {
        return readId(root.resolve(CURRENT));
    }

    public void setCurrentVersion(String id) throws IOException {
        requireId(id);
        Files.createDirectories(root);
        writeAtomically(root.resolve(CURRENT), id);
    }

    public Optional<String> nextVersion() {
        return readId(root.resolve(NEXT));
    }

    public void setNextVersion(String id) throws IOException {
        requireId(id);
        Files.createDirectories(root);
        writeAtomically(root.resolve(NEXT), id);
    }

    public void clearNextVersion() {
        try {
            Files.deleteIfExists(root.resolve(NEXT));
        } catch (IOException e) {
            // Un next-version qui reste est relu et contrôlé : il ne décide de rien seul.
        }
    }

    // ------------------------------------------------------------------ santé (F-111 / SF-111-05)

    /** Le témoin de santé : écrit par le runner quand sa liaison est établie. */
    public Path healthFile() {
        return root.resolve(HEALTH);
    }

    /** Efface le témoin avant de démarrer une version à l'essai. */
    public void clearHealth() {
        try {
            Files.deleteIfExists(healthFile());
        } catch (IOException e) {
            // Un témoin qui reste ne vaut que pour la version qu'il nomme.
        }
    }

    /** Écrit le témoin : {@code <id> <pid>}. Appelé par le runner, pas par le lanceur. */
    public void markConnected(String id, long pid) throws IOException {
        requireId(id);
        Files.createDirectories(root);
        writeAtomically(healthFile(), id + " " + pid);
    }

    /** La version qui s'est déclarée connectée, si le témoin existe et est lisible. */
    public Optional<String> connectedVersion() {
        try {
            if (!Files.isRegularFile(healthFile())) {
                return Optional.empty();
            }
            String[] parts = Files.readString(healthFile(), StandardCharsets.US_ASCII).trim().split(" ");
            return RunnerBuild.parseId(parts[0]).isPresent() ? Optional.of(parts[0]) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ rapport (F-111 / SF-111-05)

    /** Un retour arrière, à dire à la gateway par le runner revenu. */
    public record UpdateReport(String from, String to, String result, String reason) {
    }

    /** Écrit le rapport d'un retour arrière (JSON minimal, sans dépendance). */
    public void writeReport(UpdateReport report) throws IOException {
        Files.createDirectories(root);
        writeAtomically(root.resolve(REPORT), "{\"from\":" + quote(report.from()) + ",\"to\":" + quote(report.to())
                + ",\"result\":" + quote(report.result()) + ",\"reason\":" + quote(report.reason()) + "}");
    }

    /** Le fichier du rapport en attente (lu par le runner avec Jackson). */
    public Path reportFile() {
        return root.resolve(REPORT);
    }

    public void clearReport() {
        try {
            Files.deleteIfExists(reportFile());
        } catch (IOException e) {
            // Relu et renvoyé à la prochaine connexion : la gateway ignore un rapport déjà appliqué.
        }
    }

    // ------------------------------------------------------------------ rétention (F-111 / SF-111-05)

    /**
     * Garde la version courante et les {@code keepPrevious} versions installées les plus récentes <b>sous</b>
     * elle ; supprime les autres dossiers de {@code versions/} (y compris une version plus récente qui a
     * échoué). Une suppression impossible (fichier verrouillé sous Windows) est ignorée : elle sera
     * retentée à la prochaine rétention.
     *
     * @return les versions supprimées
     */
    public java.util.List<String> prune(String current, int keepPrevious) {
        java.util.List<String> removed = new java.util.ArrayList<>();
        RunnerBuild currentBuild = RunnerBuild.parseId(current).orElse(null);
        if (currentBuild == null) {
            return removed;
        }
        java.util.List<RunnerBuild> older = new java.util.ArrayList<>();
        java.util.List<String> all = installedIds();
        for (String id : all) {
            RunnerBuild build = RunnerBuild.parseId(id).orElse(null);
            if (build != null && !id.equals(current) && build.compareTo(currentBuild) < 0) {
                older.add(build);
            }
        }
        older.sort((a, b) -> b.compareTo(a));
        java.util.Set<String> kept = new java.util.HashSet<>();
        kept.add(current);
        older.stream().limit(Math.max(0, keepPrevious)).forEach(build -> kept.add(build.id()));
        for (String id : all) {
            if (!kept.contains(id) && deleteVersion(id)) {
                removed.add(id);
            }
        }
        return removed;
    }

    /** Supprime le dossier d'une version. Vrai si plus rien ne reste. */
    public boolean deleteVersion(String id) {
        if (RunnerBuild.parseId(id).isEmpty()) {
            return false;
        }
        Path dir = versionDir(id);
        if (!Files.exists(dir)) {
            return true;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            java.util.List<Path> paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Les identifiants des dossiers de {@code versions/} (validés). */
    public java.util.List<String> installedIds() {
        Path versions = root.resolve("versions");
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (!Files.isDirectory(versions)) {
            return ids;
        }
        try (java.util.stream.Stream<Path> list = Files.list(versions)) {
            list.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> RunnerBuild.parseId(name).isPresent())
                    .forEach(ids::add);
        } catch (IOException e) {
            // Dossier illisible : rien à supprimer.
        }
        return ids;
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /** Empreinte SHA-256 hexadécimale d'un fichier. */
    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = digest();
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    /** Empreinte SHA-256 hexadécimale d'octets. */
    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private Path versionDir(String id) {
        requireId(id);
        return root.resolve("versions").resolve(id);
    }

    private static void requireId(String id) {
        if (RunnerBuild.parseId(id).isEmpty()) {
            throw new IllegalArgumentException("Identifiant de version invalide : " + id);
        }
    }

    private static Optional<String> readId(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            String id = Files.readString(file, StandardCharsets.US_ASCII).trim();
            return RunnerBuild.parseId(id).isPresent() ? Optional.of(id) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        // UTF-8 : le rapport de retour porte un motif en français (les identifiants restent ASCII).
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        move(tmp, target);
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
