package fr.claudegateway.jarvis.state.releve;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.runner.RunnerIdentity;
import fr.claudegateway.runner.RunnerPollController;
import fr.claudegateway.runner.RunnerTokenAuthenticator;

/**
 * <b>Le relevé de départ</b> (F-247 / SF-247-09) : {@code POST /runner/departure}, appelé par le crochet d'arrêt du
 * runner avant qu'il coupe ses transports. Authentifié par le jeton du runner ({@code X-Runner-Token}, vérifié ici :
 * aucun filtre ne connaît ce jeton, rien n'est posé dans le contexte de sécurité — D9). L'identité (compte, poste)
 * vient <b>du jeton seul</b>. Répond en 8 s au plus.
 */
@RestController
public class RunnerDepartureController {

    private final RunnerTokenAuthenticator authenticator;
    private final JarvisReleveTriggers triggers;

    public RunnerDepartureController(RunnerTokenAuthenticator authenticator, JarvisReleveTriggers triggers) {
        this.authenticator = authenticator;
        this.triggers = triggers;
    }

    @PostMapping("/runner/departure")
    public ResponseEntity<?> departure(
            @RequestHeader(value = RunnerPollController.TOKEN_HEADER, required = false) String token) {
        Optional<RunnerIdentity> identity = authenticator.authenticate(token == null ? null : token.strip());
        if (identity.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        int ran = triggers.departure(identity.get().userId(), identity.get().hostId());
        return ResponseEntity.ok(Map.of("probes", ran));
    }
}
