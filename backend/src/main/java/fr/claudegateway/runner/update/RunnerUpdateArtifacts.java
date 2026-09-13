package fr.claudegateway.runner.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Les artefacts de <b>mise à jour du runner</b> que cette gateway sert (F-111 / SF-111-03) : le jar,
 * son empreinte, sa signature et le manifeste, déposés par la construction de l'image dans
 * {@code /app/runner-update/}.
 *
 * <p>Lus <b>une fois</b>, au démarrage — le dossier ne change pas sous un pod qui tourne. Le manifeste
 * n'est retenu que si l'empreinte du jar, <b>recalculée ici</b>, est celle qu'il annonce : un
 * empaquetage incohérent ne sert rien plutôt que de servir un fichier que tous les runners
 * refuseraient.</p>
 *
 * <p><b>La gateway ne vérifie pas la signature</b> et n'a pas à le faire : c'est le runner qui la
 * contrôle avec sa clé embarquée, précisément pour qu'une gateway compromise ne puisse rien faire
 * installer. Elle refuse seulement de servir un jar <b>non signé</b>.</p>
 */
@Component
public class RunnerUpdateArtifacts {

    private static final Logger log = LoggerFactory.getLogger(RunnerUpdateArtifacts.class);

    static final String JAR = "runner.jar";
    static final String SHA256 = "runner.jar.sha256";
    static final String SIGNATURE = "runner.jar.sig";
    static final String MANIFEST = "runner-manifest.json";

    /** Le manifeste tel que servi. Tolérant aux champs inconnus : la construction peut en ajouter. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Manifest(String id, String version, String commit, String builtAt, Integer contract,
            Integer minJava, String sha256, long size, boolean signed, String signedAt, List<String> notes) {

        public Manifest {
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    private final Path dir;
    private final Manifest manifest;
    private final String signature;

    public RunnerUpdateArtifacts(@Value("${app.runner.update-dir:}") String updateDir,
            @Value("${app.runner.jar-path:}") String jarPath, ObjectMapper objectMapper) {
        this.dir = resolveDir(updateDir, jarPath);
        Manifest read = dir == null ? null : read(dir, objectMapper);
        this.manifest = read;
        this.signature = read != null && read.signed() ? signatureOf(dir) : null;
        if (read == null) {
            log.debug("Aucune mise à jour de runner servie (dossier : {}).", dir);
        } else {
            log.info("Mise à jour de runner servie : {} ({}).", read.id(),
                    signature != null ? "signée" : "NON signée — aucun runner ne l'installera");
        }
    }

    /** Le manifeste servi, s'il est cohérent. */
    public Optional<Manifest> manifest() {
        return Optional.ofNullable(manifest);
    }

    /** Vrai si une version <b>signée</b> est servie : la seule qui peut être installée. */
    public boolean signedUpdateAvailable() {
        return manifest != null && signature != null;
    }

    /** Le jar de cette version, seulement si c'est la version servie et qu'elle est signée. */
    public Optional<Path> jar(String version) {
        return served(version).map(m -> dir.resolve(JAR));
    }

    /** L'empreinte SHA-256 de cette version servie et signée. */
    public Optional<String> sha256(String version) {
        return served(version).map(Manifest::sha256);
    }

    /** La signature Base64 de cette version servie et signée. */
    public Optional<String> signature(String version) {
        return served(version).map(m -> signature);
    }

    private Optional<Manifest> served(String version) {
        return signedUpdateAvailable() && manifest.id().equals(version) ? Optional.of(manifest) : Optional.empty();
    }

    static Path resolveDir(String updateDir, String jarPath) {
        if (updateDir != null && !updateDir.isBlank()) {
            return Path.of(updateDir.trim());
        }
        if (jarPath != null && !jarPath.isBlank()) {
            Path parent = Path.of(jarPath.trim()).toAbsolutePath().getParent();
            return parent == null ? null : parent.resolve("runner-update");
        }
        return null;
    }

    private static Manifest read(Path dir, ObjectMapper mapper) {
        Path manifestFile = dir.resolve(MANIFEST);
        Path jar = dir.resolve(JAR);
        if (!Files.isRegularFile(manifestFile) || !Files.isRegularFile(jar)) {
            return null;
        }
        try {
            Manifest manifest = mapper.readValue(manifestFile.toFile(), Manifest.class);
            if (manifest.id() == null || !manifest.id().matches("\\d+(\\.\\d+){0,2}(-\\d{12}(-[A-Za-z0-9]{1,40})?)?")
                    || manifest.sha256() == null) {
                log.warn("Manifeste de mise à jour du runner invalide ({}) : rien n'est servi.", manifestFile);
                return null;
            }
            String actual = sha256(jar);
            if (!actual.equalsIgnoreCase(manifest.sha256())) {
                log.error("Empreinte du runner servi ({}) différente du manifeste ({}) : rien n'est servi.",
                        actual, manifest.sha256());
                return null;
            }
            return manifest;
        } catch (IOException | RuntimeException e) {
            log.warn("Manifeste de mise à jour du runner illisible ({}) : rien n'est servi.", manifestFile);
            return null;
        }
    }

    private static String signatureOf(Path dir) {
        Path file = dir.resolve(SIGNATURE);
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            String value = Files.readString(file, StandardCharsets.US_ASCII).trim();
            return value.isEmpty() ? null : value;
        } catch (IOException e) {
            return null;
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
