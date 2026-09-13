package fr.claudegateway.runner.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.shared.error.ErrorResponse;

/**
 * La version du runner à installer (F-111 / SF-111-03) : manifeste, jar signé, empreinte, signature.
 *
 * <p><b>Publiques</b>, comme {@code GET /runner/download} : le runner est un client sans secret, et ce
 * qui protège une mise à jour n'est pas l'accès à ces routes mais la <b>signature</b>, que le runner
 * vérifie avec sa clé embarquée avant d'écrire quoi que ce soit. Chaque route est déclarée une par une
 * dans {@code RunnerSecurityConfig}.</p>
 *
 * <p>Une version inconnue, non signée, ou un empaquetage incohérent répondent un {@code 404} explicite,
 * jamais une erreur serveur.</p>
 */
@RestController
@RequestMapping("/runner/update")
public class RunnerUpdateController {

    static final String UNAVAILABLE = "runner_update_unavailable";

    private final RunnerUpdateArtifacts artifacts;

    public RunnerUpdateController(RunnerUpdateArtifacts artifacts) {
        this.artifacts = artifacts;
    }

    @GetMapping("/manifest")
    public ResponseEntity<?> manifest() {
        return artifacts.manifest().<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> unavailable("Aucune version du runner n'est servie par cette gateway."));
    }

    @GetMapping("/{version}")
    public ResponseEntity<?> jar(@PathVariable String version) {
        Optional<Path> jar = artifacts.jar(version);
        if (jar.isEmpty()) {
            return unavailable("Cette version du runner n'est pas servie, ou n'est pas signée.");
        }
        long size;
        try {
            size = Files.size(jar.get());
        } catch (IOException e) {
            return unavailable("Cette version du runner n'est pas lisible sur cette gateway.");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(size)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"runner.jar\"")
                .body(new FileSystemResource(jar.get()));
    }

    @GetMapping(value = "/{version}/sha256")
    public ResponseEntity<?> sha256(@PathVariable String version) {
        return text(artifacts.sha256(version));
    }

    @GetMapping(value = "/{version}/signature")
    public ResponseEntity<?> signature(@PathVariable String version) {
        return text(artifacts.signature(version));
    }

    private ResponseEntity<?> text(Optional<String> value) {
        return value.<ResponseEntity<?>>map(v -> ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(v))
                .orElseGet(() -> unavailable("Cette version du runner n'est pas servie, ou n'est pas signée."));
    }

    private static ResponseEntity<ErrorResponse> unavailable(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(UNAVAILABLE, message));
    }
}
