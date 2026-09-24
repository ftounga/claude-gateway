package fr.claudegateway.push;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.push.dto.PushSubscribeRequest;
import fr.claudegateway.push.dto.PushUnsubscribeRequest;
import fr.claudegateway.push.dto.VapidPublicKeyResponse;
import jakarta.validation.Valid;

/**
 * Les <b>abonnements Web Push</b> d'un utilisateur (F-153 / SF-153-02).
 *
 * <p>Trois gestes : s'abonner, se désabonner, lire la clé publique VAPID. L'identité vient
 * <b>toujours</b> du {@link CurrentUser} — le corps ne porte jamais d'identifiant d'utilisateur, si
 * bien qu'un compte ne peut ni lire ni modifier les abonnements d'un autre.</p>
 */
@RestController
@RequestMapping("/push")
public class PushSubscriptionController {

    private final PushSubscriptionService subscriptions;
    private final CurrentUser currentUser;

    public PushSubscriptionController(PushSubscriptionService subscriptions, CurrentUser currentUser) {
        this.subscriptions = subscriptions;
        this.currentUser = currentUser;
    }

    /** Abonne (ou ré-abonne) l'appareil courant du propriétaire. */
    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public void subscribe(@Valid @RequestBody PushSubscribeRequest request) {
        subscriptions.subscribe(currentUser.requireId(), request.endpoint(),
                request.keys().p256dh(), request.keys().auth());
    }

    /** Désabonne l'appareil courant du propriétaire (idempotent). */
    @DeleteMapping("/subscriptions")
    public ResponseEntity<Void> unsubscribe(@Valid @RequestBody PushUnsubscribeRequest request) {
        subscriptions.unsubscribe(currentUser.requireId(), request.endpoint());
        return ResponseEntity.noContent().build();
    }

    /** La clé publique VAPID (jamais la privée) ; {@code null} si le push n'est pas configuré. */
    @GetMapping("/vapid-public-key")
    public VapidPublicKeyResponse vapidPublicKey() {
        // Lecture bornée à l'utilisateur authentifié (endpoint protégé) : requireId lève sinon.
        currentUser.requireId();
        return new VapidPublicKeyResponse(subscriptions.vapidPublicKey());
    }
}
