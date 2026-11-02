package fr.claudegateway.jarvis.portrait;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Le portrait de Jarvis</b> (F-241 / SF-241-01) — {@code /api/jarvis/portrait}, authentifié, sur le seul
 * {@code user_id} du principal. <b>Aucun point d'accès administrateur</b> : personne ne lit le portrait d'un autre.
 */
@RestController
public class JarvisPortraitController {

    private final JarvisPortraitService service;
    private final CurrentUser currentUser;

    public JarvisPortraitController(JarvisPortraitService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /** {@code {"sections": {"QUI": "…"}, "enabled": true}} — chaque champ est facultatif. */
    public record UpdateRequest(Map<String, String> sections, Boolean enabled) {
    }

    /** {@code {"text": "…", "sensitive": false, "source": "ECRAN"}}. */
    public record SouvenirRequest(String text, Boolean sensitive, String source) {
    }

    /** {@code {"section": "ENTOURAGE"}}. */
    public record IntegrateRequest(String section) {
    }

    @GetMapping("/jarvis/portrait")
    public JarvisPortraitService.View get() {
        return service.view(currentUser.requireId());
    }

    @PutMapping("/jarvis/portrait")
    public ResponseEntity<JarvisPortraitService.View> put(@RequestBody(required = false) UpdateRequest body) {
        if (body == null || (body.sections() == null && body.enabled() == null)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(service.update(currentUser.requireId(), body.sections(), body.enabled()));
    }

    /** « Tout effacer » : portrait, souvenirs (et, dès SF-241-04, le journal des allusions) — suppression physique. */
    @DeleteMapping("/jarvis/portrait")
    public ResponseEntity<Void> eraseAll() {
        service.eraseAll(currentUser.requireId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/jarvis/portrait/souvenirs")
    public ResponseEntity<JarvisPortraitService.SouvenirView> add(
            @RequestBody(required = false) SouvenirRequest body) {
        if (body == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.addSouvenir(currentUser.requireId(), body.text(), body.sensitive(), body.source()));
    }

    @PatchMapping("/jarvis/portrait/souvenirs/{id}")
    public ResponseEntity<JarvisPortraitService.SouvenirView> patch(@PathVariable UUID id,
            @RequestBody(required = false) SouvenirRequest body) {
        if (body == null || (body.text() == null && body.sensitive() == null)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(service.patchSouvenir(currentUser.requireId(), id, body.text(), body.sensitive()));
    }

    @DeleteMapping("/jarvis/portrait/souvenirs/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.deleteSouvenir(currentUser.requireId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/jarvis/portrait/souvenirs/{id}/integrate")
    public ResponseEntity<JarvisPortraitService.View> integrate(@PathVariable UUID id,
            @RequestBody(required = false) IntegrateRequest body) {
        if (body == null || body.section() == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(service.integrate(currentUser.requireId(), id, body.section()));
    }
}
