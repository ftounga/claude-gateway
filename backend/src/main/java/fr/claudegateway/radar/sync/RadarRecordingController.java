package fr.claudegateway.radar.sync;

import java.io.InputStream;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarScopeResolver;
import fr.claudegateway.radar.sync.RadarRecordingDepositService.ChunkReceived;
import fr.claudegateway.radar.sync.RadarRecordingDepositService.DepositDone;
import fr.claudegateway.radar.sync.RadarRecordingDepositService.DepositOpened;
import fr.claudegateway.radar.sync.RadarRecordingDepositService.DepositRequest;
import fr.claudegateway.radar.sync.RadarRecordingDepositService.RecordingProgress;
import fr.claudegateway.teams.TeamsAccessService;

/**
 * <b>Déposer un enregistrement</b> (F-104 / SF-104-04) : ouvrir, envoyer les morceaux, finir, abandonner.
 *
 * <p><b>Droit et isolation</b> : droit Vigie d'abord, puis poste possédé (404) et activé dans la Vigie (409) ;
 * le runner n'est jamais appelé hors de ce périmètre.</p>
 */
@RestController
@RequestMapping("/radar/hosts/{hostId}/recordings")
public class RadarRecordingController {

    private final RadarRecordingDepositService deposits;
    private final RadarScopeResolver scopeResolver;
    private final TeamsAccessService teamsAccess;
    private final CurrentUser currentUser;

    public RadarRecordingController(RadarRecordingDepositService deposits, RadarScopeResolver scopeResolver,
            TeamsAccessService teamsAccess, CurrentUser currentUser) {
        this.deposits = deposits;
        this.scopeResolver = scopeResolver;
        this.teamsAccess = teamsAccess;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ResponseEntity<DepositOpened> open(@PathVariable UUID hostId,
            @RequestBody(required = false) DepositRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(deposits.open(scope(hostId), request));
    }

    /** Un morceau : corps binaire, lu borné, relayé au poste, jamais écrit ici. */
    @PutMapping(path = "/{uploadId}/chunks", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ChunkReceived chunk(@PathVariable UUID hostId, @PathVariable UUID uploadId,
            @RequestParam long offset, InputStream body) {
        return deposits.chunk(scope(hostId), uploadId, offset, body);
    }

    @PostMapping("/{uploadId}/finish")
    public DepositDone finish(@PathVariable UUID hostId, @PathVariable UUID uploadId) {
        return deposits.finish(scope(hostId), uploadId);
    }

    /** Où en est la transcription lancée par {@code finish} (F-147 / SF-147-01). */
    @GetMapping("/{uploadId}/status")
    public RecordingProgress status(@PathVariable UUID hostId, @PathVariable UUID uploadId) {
        return deposits.progress(scope(hostId), uploadId);
    }

    @DeleteMapping("/{uploadId}")
    public ResponseEntity<Void> abort(@PathVariable UUID hostId, @PathVariable UUID uploadId) {
        deposits.abort(scope(hostId), uploadId);
        return ResponseEntity.noContent().build();
    }

    private RadarScope scope(UUID hostId) {
        teamsAccess.requireAccess();
        return scopeResolver.requireInVigie(currentUser.requireId(), hostId);
    }
}
