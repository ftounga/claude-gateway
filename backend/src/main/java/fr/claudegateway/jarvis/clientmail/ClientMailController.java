package fr.claudegateway.jarvis.clientmail;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Les mails des clients, côté écran</b> (F-249 / SF-249-05) — l'état par poste et les réglages, sous le compte du
 * principal ; le poste d'autrui est introuvable (404).
 */
@RestController
public class ClientMailController {

    /** Le corps de {@code PUT …/hosts/{hostId}}. */
    public record ActiveRequest(boolean active) {
    }

    private final ClientMailSettingsService service;
    private final ClientMailIgnoreService ignore;
    private final CurrentUser currentUser;

    public ClientMailController(ClientMailSettingsService service, ClientMailIgnoreService ignore,
            CurrentUser currentUser) {
        this.service = service;
        this.ignore = ignore;
        this.currentUser = currentUser;
    }

    /** SF-249-06 — « Ignorer ces mails » : la règle est créée et appliquée. */
    @org.springframework.web.bind.annotation.PostMapping("/jarvis/client-mail/offers/{id}/accept")
    public ClientMailIgnoreService.RuleView accept(@PathVariable UUID id) {
        return ignore.accept(currentUser.requireId(), id);
    }

    /** SF-249-06 — « Garder » : la proposition est close, jamais reproposée. */
    @org.springframework.web.bind.annotation.PostMapping("/jarvis/client-mail/offers/{id}/decline")
    public ResponseEntity<Void> decline(@PathVariable UUID id) {
        ignore.decline(currentUser.requireId(), id);
        return ResponseEntity.noContent().build();
    }

    /** SF-249-06 — « Retirer » une règle : ses mails récents repassent au tri. */
    @org.springframework.web.bind.annotation.DeleteMapping("/jarvis/client-mail/rules/{id}")
    public ResponseEntity<Void> removeRule(@PathVariable UUID id) {
        ignore.removeRule(currentUser.requireId(), id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(ClientMailIgnoreService.IgnoreNotFoundException.class)
    public ResponseEntity<Map<String, String>> ignoreNotFound(ClientMailIgnoreService.IgnoreNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found", "message", e.getMessage()));
    }

    @ExceptionHandler(ClientMailIgnoreService.OfferClosedException.class)
    public ResponseEntity<Map<String, String>> closed(ClientMailIgnoreService.OfferClosedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "offer_closed", "message", e.getMessage()));
    }

    @GetMapping("/jarvis/client-mail")
    public ClientMailSettingsService.Overview overview() {
        return service.overview(currentUser.requireId());
    }

    @PutMapping("/jarvis/client-mail/settings")
    public ClientMailSettingsService.SettingsView settings(@RequestBody ClientMailSettingsService.SettingsView body) {
        return service.save(currentUser.requireId(), body);
    }

    @PutMapping("/jarvis/client-mail/hosts/{hostId}")
    public ClientMailSettingsService.ClientView host(@PathVariable UUID hostId, @RequestBody ActiveRequest body) {
        return service.setActive(currentUser.requireId(), hostId, body != null && body.active());
    }

    @ExceptionHandler(ClientMailSettingsService.InvalidSettingsException.class)
    public ResponseEntity<Map<String, String>> invalid(ClientMailSettingsService.InvalidSettingsException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_settings", "message", e.getMessage()));
    }

    @ExceptionHandler(ClientMailSettingsService.ClientNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(ClientMailSettingsService.ClientNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found", "message", e.getMessage()));
    }
}
