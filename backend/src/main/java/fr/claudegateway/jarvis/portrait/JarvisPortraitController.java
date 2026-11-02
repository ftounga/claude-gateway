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
    private final JarvisInterviewService interviews;
    private final CurrentUser currentUser;

    public JarvisPortraitController(JarvisPortraitService service, JarvisInterviewService interviews,
            CurrentUser currentUser) {
        this.service = service;
        this.interviews = interviews;
        this.currentUser = currentUser;
    }

    /**
     * {@code {"sections": {"QUI": "…"}, "enabled": true, "interviewed": true}} — chaque champ est facultatif ;
     * {@code interviewed} (SF-241-02) : la validation d'un entretien, qui date le dernier entretien.
     */
    public record UpdateRequest(Map<String, String> sections, Boolean enabled, Boolean interviewed) {
    }

    /** SF-241-02 — les réponses de l'entretien à ranger (rien n'est stocké). */
    public record DraftRequest(java.util.List<JarvisInterviewService.Answer> answers) {
    }

    /** SF-241-02 — l'écran peut-il proposer l'entretien (cadence D13) ? */
    @GetMapping("/jarvis/portrait/interview")
    public JarvisInterviewService.Offer interviewOffer() {
        return interviews.offer(currentUser.requireId());
    }

    /** SF-241-02 — l'entretien a été proposé et remis à plus tard. */
    @PostMapping("/jarvis/portrait/interview/offered")
    public JarvisInterviewService.Offer interviewOffered() {
        return interviews.offered(currentUser.requireId());
    }

    /** SF-241-02 — le brouillon rangé en sections : rendu, <b>jamais écrit</b>. */
    @PostMapping("/jarvis/portrait/interview/draft")
    public ResponseEntity<JarvisInterviewService.Draft> interviewDraft(
            @RequestBody(required = false) DraftRequest body) {
        if (body == null || body.answers() == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(interviews.draft(currentUser.requireId(), body.answers()));
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
        java.util.UUID userId = currentUser.requireId();
        JarvisPortraitService.View view = service.update(userId, body.sections(), body.enabled());
        if (Boolean.TRUE.equals(body.interviewed())) {
            service.markInterviewed(userId);
            view = service.view(userId);
        }
        return ResponseEntity.ok(view);
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
