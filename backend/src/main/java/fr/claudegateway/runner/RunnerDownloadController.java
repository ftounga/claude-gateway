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
 * Téléchargement du binaire runner (F-38 / SF-38-03) : {@code GET /runner/download} sert le fat-jar
 * {@code claude-runner.jar} déposé à un chemin configurable ({@code app.runner.jar-path}).
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

    private final String jarPath;

    public RunnerDownloadController(@Value("${app.runner.jar-path:}") String jarPath) {
        this.jarPath = jarPath == null ? "" : jarPath.trim();
    }

    @GetMapping("/download")
    public ResponseEntity<?> download() {
        if (jarPath.isEmpty()) {
            log.debug("Téléchargement runner indisponible : app.runner.jar-path non configuré");
            return unavailable();
        }
        Path path = Path.of(jarPath);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            log.warn("Téléchargement runner indisponible : {} absent ou illisible", path);
            return unavailable();
        }
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            log.warn("Téléchargement runner indisponible : taille de {} illisible", path, e);
            return unavailable();
        }
        Resource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(size)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + FILENAME + "\"")
                .body(resource);
    }

    private ResponseEntity<ErrorResponse> unavailable() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse("runner_jar_unavailable",
                        "Le binaire du runner n'est pas disponible sur cette gateway."));
    }
}
