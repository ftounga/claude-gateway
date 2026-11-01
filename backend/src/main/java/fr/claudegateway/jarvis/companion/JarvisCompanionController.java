package fr.claudegateway.jarvis.companion;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.shared.error.ErrorResponse;

/**
 * <b>Les téléphones de l'utilisateur</b> (F-232 / SF-232-03) — chaîne principale, JWT : afficher un code
 * d'appairage, lister les téléphones appairés, en révoquer un. L'identité vient <b>exclusivement</b> du
 * {@link CurrentUser} ; le téléphone d'autrui est introuvable (404).
 */
@RestController
@RequestMapping("/jarvis/companion")
public class JarvisCompanionController {

    private final JarvisCompanionPairingService pairing;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public JarvisCompanionController(JarvisCompanionPairingService pairing, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.pairing = pairing;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    @PostMapping("/pairing-code")
    public ResponseEntity<?> pairingCode() {
        atelierAccess.requireAccess();
        try {
            return ResponseEntity.ok(pairing.createCode(currentUser.requireId()));
        } catch (JarvisCompanionPairingService.LimitReached e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("companion_limit",
                    "Cinq téléphones sont déjà appairés : révoquez-en un d'abord."));
        }
    }

    @GetMapping("/devices")
    public List<JarvisCompanionPairingService.DeviceView> devices() {
        atelierAccess.requireAccess();
        return pairing.list(currentUser.requireId());
    }

    @DeleteMapping("/devices/{id}")
    public ResponseEntity<?> revoke(@PathVariable UUID id) {
        atelierAccess.requireAccess();
        try {
            pairing.revoke(currentUser.requireId(), id);
            return ResponseEntity.noContent().build();
        } catch (JarvisCompanionPairingService.NotFound e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("not_found", "Téléphone introuvable."));
        }
    }
}
