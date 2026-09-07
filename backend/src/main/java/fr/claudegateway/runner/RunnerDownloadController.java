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
 * Téléchargement du runner, en <b>deux formats</b> :
 *
 * <ul>
 *   <li>{@code GET /runner/download} (F-38 / SF-38-03) — le fat-jar {@code claude-runner.jar},
 *       2,5 Mo, qui <b>suppose une JVM 21</b> sur le poste ;</li>
 *   <li>{@code GET /runner/download/windows} (F-44 / SF-44-02) — le paquet autonome Windows, qui
 *       embarque <b>sa propre JVM</b> et ne suppose rien. C'est le format des postes d'entreprise
 *       où aucun Java 21 n'est installable.</li>
 * </ul>
 *
 * <p>Les deux chemins sont configurables ({@code app.runner.jar-path},
 * {@code app.runner.windows-package-path}) et le second <b>n'a pas remplacé</b> le premier : un
 * développeur qui a déjà Java n'a aucune raison de télécharger 39 Mo pour en utiliser 2,5.</p>
 *
 * <p>Endpoint <b>public</b> (le jar est un client, pas une donnée utilisateur : il ne contient ni
 * jeton ni secret — l'appairage se fait après, avec un code généré dans l'UI). Il est servi par la
 * chaîne de sécurité dédiée {@code /runner/**} ({@link RunnerSecurityConfig}) : la chaîne principale
 * reste inchangée et aucun filtre utilisateur n'est traversé.</p>
 *
 * <p>Quand aucun chemin n'est configuré, ou que le fichier est absent/illisible, la réponse est un
 * <b>404 {@code runner_jar_unavailable}</b> explicite plutôt qu'une erreur serveur : le jar n'est pas
 * empaqueté dans l'image du backend (décision SF-38-03), il est déposé côté déploiement.</p>
 */
@RestController
@RequestMapping("/runner")
public class RunnerDownloadController {

    private static final Logger log = LoggerFactory.getLogger(RunnerDownloadController.class);

    private static final String FILENAME = "claude-runner.jar";
    private static final String PACKAGE_FILENAME = "claude-runner-windows-x64.zip";

    private final String jarPath;
    private final String windowsPackagePath;

    public RunnerDownloadController(@Value("${app.runner.jar-path:}") String jarPath,
            @Value("${app.runner.windows-package-path:}") String windowsPackagePath) {
        this.jarPath = jarPath == null ? "" : jarPath.trim();
        this.windowsPackagePath = windowsPackagePath == null ? "" : windowsPackagePath.trim();
    }

    @GetMapping("/download")
    public ResponseEntity<?> download() {
        return serve(jarPath, FILENAME, "runner_jar_unavailable",
                "Le binaire du runner n'est pas disponible sur cette gateway.");
    }

    /**
     * Paquet autonome Windows (F-44). Un {@code 404} ici ne décrit pas le même incident qu'un
     * {@code 404} sur le jar — d'où un code d'erreur distinct (D2) : l'un dit que le jar manque,
     * l'autre que le paquet n'a pas été empaqueté dans l'image. Les confondre ferait chercher au
     * mauvais endroit.
     */
    @GetMapping("/download/windows")
    public ResponseEntity<?> downloadWindowsPackage() {
        return serve(windowsPackagePath, PACKAGE_FILENAME, "runner_package_unavailable",
                "Le paquet autonome Windows n'est pas disponible sur cette gateway.");
    }

    /**
     * Formats réellement disponibles sur cette gateway (F-44 / SF-44-02).
     *
     * <p>L'écran l'interroge pour <b>masquer</b> un format absent plutôt que d'offrir un lien qui
     * répondrait 404 (D3) : une gateway déployée avant F-44 n'empaquette pas le paquet Windows, et
     * doit rester utilisable avec le seul jar.</p>
     */
    @GetMapping("/download/formats")
    public RunnerDownloadFormats formats() {
        return new RunnerDownloadFormats(exists(jarPath), exists(windowsPackagePath));
    }

    /** Disponibilité des deux formats de téléchargement du runner. */
    public record RunnerDownloadFormats(boolean jar, boolean windowsPackage) {
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
