package fr.claudegateway.vigie;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.runner.RunnerIdentity;
import fr.claudegateway.runner.RunnerTokenAuthenticator;

/**
 * <b>Le runner rapporte l'état de mise en service de la Vigie</b> (F-122 / SF-122-02).
 *
 * <p><b>Authentification (décision D9)</b>, comme les autres endpoints {@code /runner/**} : le jeton
 * runner voyage dans {@code X-Runner-Token} et est vérifié <b>ici</b> ({@link RunnerTokenAuthenticator}),
 * jamais par un filtre HTTP. Refus en <b>401 générique</b> ; rien n'est jamais posé dans le
 * {@code SecurityContext} — un jeton runner n'est pas un JWT utilisateur.</p>
 *
 * <p><b>Isolation</b> : l'instantané est rangé pour <b>le poste du jeton</b> ({@link RunnerIdentity}),
 * jamais pour un poste nommé dans le corps. Un runner ne peut donc rapporter que pour lui-même.</p>
 *
 * <p>La <b>production</b> de l'instantané (exécution des sondes) est portée par SF-122-03.</p>
 */
@RestController
@RequestMapping("/runner/vigie")
public class RunnerVigieReadinessController {

    /** En-tête portant le jeton runner. Volontairement pas {@code Authorization} (D9). */
    public static final String TOKEN_HEADER = "X-Runner-Token";

    private final RunnerTokenAuthenticator authenticator;
    private final VigieReadinessService readinessService;

    public RunnerVigieReadinessController(RunnerTokenAuthenticator authenticator,
            VigieReadinessService readinessService) {
        this.authenticator = authenticator;
        this.readinessService = readinessService;
    }

    /** Range le dernier instantané de readiness pour le poste du jeton. */
    @PostMapping(value = "/readiness", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> report(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody VigieReadinessSnapshot snapshot) {
        Optional<RunnerIdentity> identity = authenticator.authenticate(trim(token));
        if (identity.isEmpty()) {
            // 401 générique : on ne dit pas si le jeton est inconnu, expiré ou révoqué.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Jeton runner refusé."));
        }
        readinessService.report(identity.get().hostId(), snapshot);
        return ResponseEntity.ok(Map.of("status", "stored"));
    }

    private static String trim(String token) {
        return token == null ? "" : token.strip();
    }
}
