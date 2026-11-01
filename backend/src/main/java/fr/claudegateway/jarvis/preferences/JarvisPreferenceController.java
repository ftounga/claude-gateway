package fr.claudegateway.jarvis.preferences;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Les préférences de Jarvis</b> (F-235 / SF-235-09, D18) — {@code GET/PUT /api/jarvis/preferences}, authentifié,
 * sur la seule ligne du {@code user_id} courant.
 */
@RestController
public class JarvisPreferenceController {

    private final JarvisPreferenceService service;
    private final CurrentUser currentUser;

    public JarvisPreferenceController(JarvisPreferenceService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /** Le corps d'une mise à jour : {@code {"humour": "NONE" | "LIGHT"}}. */
    public record UpdateRequest(String humour) {
    }

    @GetMapping("/jarvis/preferences")
    public JarvisPreferenceService.Preferences get() {
        return service.get(currentUser.requireId());
    }

    @PutMapping("/jarvis/preferences")
    public ResponseEntity<JarvisPreferenceService.Preferences> put(@RequestBody(required = false) UpdateRequest body) {
        JarvisHumour humour = body == null ? null : JarvisHumour.parse(body.humour());
        if (humour == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(service.setHumour(currentUser.requireId(), humour));
    }
}
