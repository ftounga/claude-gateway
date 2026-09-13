package fr.claudegateway.radar.sync;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.radar.InvalidRadarInputException;
import fr.claudegateway.radar.RadarNotFoundException;
import fr.claudegateway.radar.RadarStateConflictException;
import fr.claudegateway.teams.TeamsAccessService;
import fr.claudegateway.radar.RadarSync;
import fr.claudegateway.runner.RunnerIdentity;
import fr.claudegateway.runner.RunnerTokenAuthenticator;

/**
 * <b>Le runner rend compte de la synchro du soir</b> (F-100 / SF-100-02).
 *
 * <p>Mêmes gardes que la remontée des captures (F-90) : <b>le jeton runner</b> vérifié ici (D9 — aucun
 * filtre HTTP ne sait le lire, rien n'est posé dans le {@code SecurityContext}), refus <b>401 générique</b> ;
 * <b>le périmètre vient du jeton</b> (compte et poste), jamais du corps — une synchro d'un autre poste est
 * introuvable (404). Une synchro close répond <b>409</b> avec son statut : le runner s'arrête.</p>
 */
@RestController
@RequestMapping("/runner/radar/syncs/{syncId}")
public class RunnerRadarSyncController {

    public static final String TOKEN_HEADER = "X-Runner-Token";

    private final RunnerTokenAuthenticator authenticator;
    private final RadarSyncSessionService sessions;
    private final RadarSyncBatchService batches;
    private final TeamsAccessService teamsAccess;

    public RunnerRadarSyncController(RunnerTokenAuthenticator authenticator, RadarSyncSessionService sessions,
            RadarSyncBatchService batches, TeamsAccessService teamsAccess) {
        this.authenticator = authenticator;
        this.sessions = sessions;
        this.batches = batches;
        this.teamsAccess = teamsAccess;
    }

    /**
     * Un lot de la collecte Teams (SF-100-03) : déposé dans la file d'analyse, puis les curseurs avancent.
     * <b>Produire</b> demande le droit (comme les captures de F-90) : sans lui, rien n'entre.
     */
    @PostMapping(value = "/batches", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> batch(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @PathVariable UUID syncId, @RequestBody(required = false) JsonNode body) {
        Optional<RunnerIdentity> identity = authenticator.authenticate(token == null ? "" : token.strip());
        if (identity.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Jeton runner refusé."));
        }
        if (!teamsAccess.hasAccess(identity.get().userId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Ce compte n'a pas le droit du Radar : les échanges ne peuvent pas remonter."));
        }
        try {
            RadarSyncBatchService.BatchReceipt receipt = batches.submit(identity.get(), syncId, body,
                    RadarSyncCursor.SOURCE_TEAMS);
            Map<String, Object> answer = new java.util.LinkedHashMap<>();
            answer.put("status", receipt.status().name());
            if (receipt.batchId() != null) {
                answer.put("batchId", receipt.batchId().toString());
                answer.put("batchStatus", receipt.batchStatus());
                answer.put("duplicate", receipt.duplicate());
            }
            answer.put("cursors", receipt.cursors());
            return ResponseEntity.ok(answer);
        } catch (RadarNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Synchro introuvable."));
        } catch (RadarSyncSessionService.SyncClosedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", e.status().name()));
        } catch (RadarStateConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "CANCELLED"));
        } catch (InvalidRadarInputException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/progress", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> progress(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @PathVariable UUID syncId, @RequestBody(required = false) JsonNode body) {
        return guarded(token, identity -> sessions.progress(identity, syncId, body));
    }

    @PostMapping(value = "/finish", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> finish(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @PathVariable UUID syncId, @RequestBody(required = false) JsonNode body) {
        return guarded(token, identity -> sessions.finish(identity, syncId, body));
    }

    private ResponseEntity<Map<String, String>> guarded(String token, Function<RunnerIdentity, RadarSync> action) {
        Optional<RunnerIdentity> identity = authenticator.authenticate(token == null ? "" : token.strip());
        if (identity.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Jeton runner refusé."));
        }
        try {
            RadarSync sync = action.apply(identity.get());
            return ResponseEntity.ok(Map.of("status", sync.getStatus().name()));
        } catch (RadarNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Synchro introuvable."));
        } catch (RadarSyncSessionService.SyncClosedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", e.status().name()));
        } catch (InvalidRadarInputException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }
}
