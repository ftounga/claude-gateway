package fr.claudegateway.jarvis.companion;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.jarvis.companion.JarvisCompanionService.CompanionLimitException;
import fr.claudegateway.jarvis.companion.JarvisCompanionService.CompanionNotFoundException;
import fr.claudegateway.jarvis.companion.JarvisCompanionService.PairingCode;
import fr.claudegateway.shared.error.ErrorResponse;

/**
 * <b>Les compagnons du compte</b> (F-232 / SF-232-03), côté écran Jarvis : générer un code d'appairage, voir les
 * téléphones appairés, en révoquer un. Chaîne utilisateur (JWT) ; l'identité vient <b>exclusivement</b> du
 * {@link CurrentUser}.
 */
@RestController
@RequestMapping("/jarvis/companions")
public class JarvisCompanionController {

    private final JarvisCompanionService service;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public JarvisCompanionController(JarvisCompanionService service, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    @PostMapping("/pairing-code")
    public PairingCode pairingCode() {
        atelierAccess.requireAccess();
        return service.createPairingCode(currentUser.requireId());
    }

    @GetMapping
    public List<CompanionView> list() {
        atelierAccess.requireAccess();
        return service.active(currentUser.requireId()).stream()
                .map(c -> new CompanionView(c.getId(), c.getLabel(), c.getPairedAt(), c.getLastSeenAt()))
                .toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        atelierAccess.requireAccess();
        service.revoke(currentUser.requireId(), id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(CompanionLimitException.class)
    ResponseEntity<ErrorResponse> limit(CompanionLimitException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("jarvis_companion_limit", ex.getMessage()));
    }

    @ExceptionHandler(CompanionNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(CompanionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("jarvis_companion_not_found", ex.getMessage()));
    }

    /** Un téléphone appairé. Jamais l'empreinte du jeton. */
    public record CompanionView(UUID id, String label, OffsetDateTime pairedAt, OffsetDateTime lastSeenAt) {
    }
}
