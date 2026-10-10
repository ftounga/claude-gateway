package fr.claudegateway.notifications;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.shared.error.ErrorResponse;

/** La cloche (F-185 / SF-185-04). Tout est scellé par le {@code user_id} du jeton. */
@RestController
@RequestMapping("/notifications")
public class NotificationCenterController {

    private final NotificationCenterService center;
    private final NotificationPreferenceService preferences;
    private final CurrentUser currentUser;

    public NotificationCenterController(NotificationCenterService center,
            NotificationPreferenceService preferences, CurrentUser currentUser) {
        this.center = center;
        this.preferences = preferences;
        this.currentUser = currentUser;
    }

    /** Préférences (F-185 / SF-185-06), avec le catalogue des événements. */
    @GetMapping("/preferences")
    public NotificationPreferencesView preferences() {
        return preferences.get(currentUser.requireId());
    }

    /** 400 lisible : événement inconnu ou critique (D5), heure invalide, borne seule, fuseau inconnu. */
    @PutMapping("/preferences")
    public ResponseEntity<?> savePreferences(@RequestBody NotificationPreferencesRequest request) {
        UUID userId = currentUser.requireId();
        try {
            return ResponseEntity.ok(preferences.save(userId, request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("validation_error", e.getMessage()));
        }
    }

    @GetMapping
    public NotificationView list() {
        return center.list(currentUser.requireId());
    }

    /** 404 pour la notification d'un autre compte : indiscernable d'une notification inexistante. */
    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id) {
        return center.markRead(currentUser.requireId(), id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead() {
        center.markAllRead(currentUser.requireId());
        return ResponseEntity.noContent().build();
    }
}
