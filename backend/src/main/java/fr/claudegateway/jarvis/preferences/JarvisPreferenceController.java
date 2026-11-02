package fr.claudegateway.jarvis.preferences;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.jarvis.capture.JarvisCaptureService;

/**
 * <b>Les préférences de Jarvis</b> (F-235 / SF-235-09, D18) — {@code GET/PUT /api/jarvis/preferences}, authentifié,
 * sur la seule ligne du {@code user_id} courant. F-239 / SF-239-03 : {@code captureAudio} (ADMIN seulement ; l'éteindre
 * efface les captures du compte).
 */
@RestController
public class JarvisPreferenceController {

    private final JarvisPreferenceService service;
    private final CurrentUser currentUser;
    private final AdminService adminService;
    private final JarvisCaptureService captures;

    public JarvisPreferenceController(JarvisPreferenceService service, CurrentUser currentUser,
            AdminService adminService, JarvisCaptureService captures) {
        this.service = service;
        this.currentUser = currentUser;
        this.adminService = adminService;
        this.captures = captures;
    }

    /** Le corps d'une mise à jour : {@code {"humour": "NONE" | "LIGHT"}} et / ou {@code {"captureAudio": true}}. */
    public record UpdateRequest(String humour, Boolean captureAudio) {
    }

    @GetMapping("/jarvis/preferences")
    public JarvisPreferenceService.Preferences get() {
        return service.get(currentUser.requireId());
    }

    @PutMapping("/jarvis/preferences")
    public ResponseEntity<JarvisPreferenceService.Preferences> put(@RequestBody(required = false) UpdateRequest body) {
        if (body == null || (body.humour() == null && body.captureAudio() == null)) {
            return ResponseEntity.badRequest().build();
        }
        java.util.UUID userId = currentUser.requireId();
        JarvisPreferenceService.Preferences result = null;
        if (body.humour() != null) {
            JarvisHumour humour = JarvisHumour.parse(body.humour());
            if (humour == null) {
                return ResponseEntity.badRequest().build();
            }
            result = service.setHumour(userId, humour);
        }
        if (body.captureAudio() != null) {
            if (body.captureAudio() && !adminService.isAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            result = service.setCaptureAudio(userId, body.captureAudio());
            if (!body.captureAudio()) {
                captures.purgeUser(userId);
            }
        }
        return ResponseEntity.ok(result);
    }
}
