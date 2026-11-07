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
    private final CurrentUser currentUser;

    public ClientMailController(ClientMailSettingsService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
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
