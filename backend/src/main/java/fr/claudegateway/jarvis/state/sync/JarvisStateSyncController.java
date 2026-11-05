package fr.claudegateway.jarvis.state.sync;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>« Synchroniser »</b> (F-247 / SF-247-16) dans « Ce que je sais » : lancer le passage d'un client (202, ou 200 s'il
 * travaille déjà), l'annuler, et relire la progression de chaque client. Tout sous le {@code user_id} du principal ;
 * client d'un autre compte ⇒ 404 ; registre coupé ⇒ 409 dit.
 */
@RestController
public class JarvisStateSyncController {

    private final JarvisStateSyncService syncs;
    private final CurrentUser currentUser;

    public JarvisStateSyncController(JarvisStateSyncService syncs, CurrentUser currentUser) {
        this.syncs = syncs;
        this.currentUser = currentUser;
    }

    @PostMapping("/jarvis/state/clients/{hostId}/sync")
    public ResponseEntity<?> start(@PathVariable UUID hostId, @RequestBody(required = false) StartRequest body) {
        UUID userId = currentUser.requireId();
        try {
            JarvisStateSyncService.Started started = syncs.start(userId, hostId,
                    body == null ? null : body.notesEnvelopeEur());
            return ResponseEntity.status(started.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(started.sync());
        } catch (JarvisStateSyncService.NotFound e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (JarvisStateSyncService.Refused e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", e.code(), "message", e.getMessage()));
        }
    }

    @PostMapping("/jarvis/state/clients/{hostId}/sync/cancel")
    public ResponseEntity<JarvisStateSyncService.SyncView> cancel(@PathVariable UUID hostId) {
        UUID userId = currentUser.requireId();
        try {
            return ResponseEntity.ok(syncs.cancel(userId, hostId));
        } catch (JarvisStateSyncService.NotFound e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /** SF-247-17 — l'enveloppe ponctuelle des constats sur l'historique ({@code null} = défaut ; 0 = sans constats). */
    public record StartRequest(java.math.BigDecimal notesEnvelopeEur) {
    }

    /** SF-247-17 — l'enveloppe par défaut d'un passage (montrée avant le lancement). */
    @GetMapping("/jarvis/state/syncs/envelope")
    public JarvisStateSyncService.Envelope envelope() {
        currentUser.requireId();
        return syncs.envelopeInfo();
    }

    @GetMapping("/jarvis/state/syncs")
    public List<JarvisStateSyncService.SyncView> latest() {
        return syncs.latest(currentUser.requireId());
    }
}
