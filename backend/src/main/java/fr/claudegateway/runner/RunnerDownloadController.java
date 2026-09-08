package fr.claudegateway.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Téléchargement du runner, en <b>quatre formats</b> :
 *
 * <ul>
 *   <li>{@code GET /runner/download} (F-38 / SF-38-03) — le fat-jar {@code claude-runner.jar},
 *       2,5 Mo, qui <b>suppose une JVM 21</b> sur le poste ;</li>
 *   <li>{@code GET /runner/download/windows} (F-44 / SF-44-02) — le paquet autonome Windows, qui
 *       embarque <b>sa propre JVM</b> et ne suppose rien ;</li>
 *   <li>{@code GET /runner/download/macos-aarch64} et {@code GET /runner/download/macos-x64}
 *       (F-44 / SF-44-03) — les mêmes pour macOS, Apple Silicon et Intel. Ils existent parce que
 *       l'hypothèse « un développeur macOS a déjà un JDK » a été démentie par un poste Mac
 *       d'entreprise verrouillé : ni droits administrateur, ni Homebrew, ni JDK.</li>
 * </ul>
 *
 * <p>Les quatre chemins sont configurables ({@code app.runner.jar-path},
 * {@code app.runner.windows-package-path}, {@code app.runner.macos-aarch64-package-path},
 * {@code app.runner.macos-x64-package-path}) et les paquets <b>n'ont pas remplacé</b> le jar : un
 * développeur qui a déjà Java n'a aucune raison de télécharger 39 Mo pour en utiliser 2,5.</p>
 *
 * <p>Endpoints <b>publics</b> (le runner est un client, pas une donnée utilisateur : il ne contient
 * ni jeton ni secret — l'appairage se fait après, avec un code généré dans l'UI). Ils sont servis
 * par la chaîne de sécurité dédiée {@code /runner/**} ({@link RunnerSecurityConfig}) : la chaîne
 * principale reste inchangée et aucun filtre utilisateur n'est traversé.</p>
 *
 * <p>Quand aucun chemin n'est configuré, ou que le fichier est absent/illisible, la réponse est un
 * <b>404</b> explicite plutôt qu'une erreur serveur : un binaire manquant est un défaut
 * d'empaquetage, pas une panne, et l'écran doit pouvoir le lire pour masquer le format.</p>
 */
@RestController
@RequestMapping("/runner")
public class RunnerDownloadController {

    private static final Logger log = LoggerFactory.getLogger(RunnerDownloadController.class);

    private static final String FILENAME = "claude-runner.jar";
    private static final String WINDOWS_FILENAME = "claude-runner-windows-x64.zip";
    private static final String MACOS_AARCH64_FILENAME = "claude-runner-macos-aarch64.tar.gz";
    private static final String MACOS_X64_FILENAME = "claude-runner-macos-x64.tar.gz";

    /**
     * Code d'erreur commun aux paquets autonomes (D2 de SF-44-03). Il reste distinct de
     * {@code runner_jar_unavailable} — « le jar manque » et « le paquet n'a pas été empaqueté » ne
     * sont pas le même incident d'exploitation — mais il est le <b>même</b> d'un paquet à l'autre :
     * c'est bien le même incident, et ce qui change est <i>lequel</i>, que le message nomme.
     */
    private static final String PACKAGE_UNAVAILABLE = "runner_package_unavailable";

    private final String jarPath;
    private final String windowsPackagePath;
    private final String macosAarch64PackagePath;
    private final String macosX64PackagePath;

    public RunnerDownloadController(@Value("${app.runner.jar-path:}") String jarPath,
            @Value("${app.runner.windows-package-path:}") String windowsPackagePath,
            @Value("${app.runner.macos-aarch64-package-path:}") String macosAarch64PackagePath,
            @Value("${app.runner.macos-x64-package-path:}") String macosX64PackagePath) {
        this.jarPath = trimmed(jarPath);
        this.windowsPackagePath = trimmed(windowsPackagePath);
        this.macosAarch64PackagePath = trimmed(macosAarch64PackagePath);
        this.macosX64PackagePath = trimmed(macosX64PackagePath);
    }

    @GetMapping("/download")
    public ResponseEntity<?> download() {
        return serve(jarPath, FILENAME, "runner_jar_unavailable",
                "Le binaire du runner n'est pas disponible sur cette gateway.");
    }

    /**
     * Paquet autonome Windows (F-44 / SF-44-02). Un {@code 404} ici ne décrit pas le même incident
     * qu'un {@code 404} sur le jar — d'où un code d'erreur distinct : l'un dit que le jar manque,
     * l'autre que le paquet n'a pas été empaqueté dans l'image. Les confondre ferait chercher au
     * mauvais endroit.
     */
    @GetMapping("/download/windows")
    public ResponseEntity<?> downloadWindowsPackage() {
        return serve(windowsPackagePath, WINDOWS_FILENAME, PACKAGE_UNAVAILABLE,
                "Le paquet autonome Windows n'est pas disponible sur cette gateway.");
    }

    /**
     * Paquet autonome macOS Apple Silicon (F-44 / SF-44-03). Une route par architecture, jamais un
     * paramètre {@code ?arch=} : le cache et les liens directs resteraient ambigus, et une valeur
     * inconnue devrait être arbitrée (D1).
     */
    @GetMapping("/download/macos-aarch64")
    public ResponseEntity<?> downloadMacosAarch64Package() {
        return serve(macosAarch64PackagePath, MACOS_AARCH64_FILENAME, PACKAGE_UNAVAILABLE,
                "Le paquet autonome macOS Apple Silicon (aarch64) n'est pas disponible sur cette gateway.");
    }

    /** Paquet autonome macOS Intel (F-44 / SF-44-03). */
    @GetMapping("/download/macos-x64")
    public ResponseEntity<?> downloadMacosX64Package() {
        return serve(macosX64PackagePath, MACOS_X64_FILENAME, PACKAGE_UNAVAILABLE,
                "Le paquet autonome macOS Intel (x64) n'est pas disponible sur cette gateway.");
    }

    /**
     * Formats réellement disponibles sur cette gateway (F-44 / SF-44-02, étendu par SF-44-03).
     *
     * <p>L'écran l'interroge pour <b>masquer</b> un format absent plutôt que d'offrir un lien qui
     * répondrait 404 (D3) : une gateway déployée avant F-44 n'empaquette aucun paquet, une gateway
     * déployée entre SF-44-02 et SF-44-03 n'a que celui de Windows, et toutes deux doivent rester
     * utilisables.</p>
     */
    @GetMapping("/download/formats")
    public RunnerDownloadFormats formats() {
        return new RunnerDownloadFormats(exists(jarPath), exists(windowsPackagePath),
                exists(macosAarch64PackagePath), exists(macosX64PackagePath));
    }

    /**
     * Disponibilité des formats de téléchargement du runner.
     *
     * <p>Les champs s'ajoutent, ne se remplacent pas : un frontend antérieur à SF-44-03 continue de
     * lire {@code jar} et {@code windowsPackage} sans rien savoir des deux suivants.</p>
     */
    public record RunnerDownloadFormats(boolean jar, boolean windowsPackage,
            boolean macosAarch64Package, boolean macosX64Package) {
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean exists(String configuredPath) {
        if (configuredPath.isEmpty()) {
            return false;
        }
        Path path = Path.of(configuredPath);
        return Files.isRegularFile(path) && Files.isReadable(path);
    }

    /**
     * Sert un fichier local, ou rend un {@code 404} explicite. Jamais d'erreur serveur : un binaire
     * absent est un défaut d'empaquetage, pas une panne — et l'écran doit pouvoir le lire pour
     * masquer le format plutôt que d'offrir un lien mort.
     */
    private ResponseEntity<?> serve(String configuredPath, String filename, String errorCode,
            String message) {
        if (configuredPath.isEmpty()) {
            log.debug("Téléchargement indisponible ({}) : chemin non configuré", filename);
            return unavailable(errorCode, message);
        }
        Path path = Path.of(configuredPath);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            log.warn("Téléchargement indisponible ({}) : {} absent ou illisible", filename, path);
            return unavailable(errorCode, message);
        }
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            log.warn("Téléchargement indisponible ({}) : taille de {} illisible", filename, path, e);
            return unavailable(errorCode, message);
        }
        Resource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(size)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(resource);
    }

    private ResponseEntity<ErrorResponse> unavailable(String errorCode, String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(errorCode, message));
    }
}
