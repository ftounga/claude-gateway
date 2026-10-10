package fr.claudegateway.notifications;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/** La cloche (F-185 / SF-185-04). Tout est scellé par le {@code user_id} du jeton. */
@RestController
@RequestMapping("/notifications")
public class NotificationCenterController {

    private final NotificationCenterService center;
    private final CurrentUser currentUser;

    public NotificationCenterController(NotificationCenterService center, CurrentUser currentUser) {
        this.center = center;
        this.currentUser = currentUser;
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
