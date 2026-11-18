package fr.claudegateway.jarvis.fastbrain;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Les travaux vivants de Jarvis</b> (F-270 / SF-270-01) — {@code GET /api/jarvis/works} : ce qui tourne en fond pour
 * le compte (relu par l'écran, identique depuis n'importe quel pod) ; {@code POST /api/jarvis/works} : l'écran annonce un
 * tour direct né à la voix avant de l'ouvrir. Tout est filtré par l'utilisateur authentifié.
 */
@RestController
public class JarvisWorksController {

    static final int MAX_LABEL = 300;

    private final JarvisWorksService service;
    private final CurrentUser currentUser;

    public JarvisWorksController(JarvisWorksService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /** Un travail tel que l'écran le lit. */
    public record WorkView(String id, String origin, UUID subjectId, String subjectName, Integer chantier, String name,
            String label, String phase, String since, String endedAt) {

        static WorkView of(JarvisWorks.Work w) {
            return new WorkView(w.id(), w.origin().name(), w.subjectId(), w.subjectName(), w.chantier(), w.name(),
                    w.label(), w.phase().name(), w.since().toString(), w.endedAt() == null ? null : w.endedAt().toString());
        }
    }

    public record WorksResponse(List<WorkView> works) {
    }

    /** L'annonce d'un tour direct : identifiant tiré par l'écran, sujet du compte, chantier facultatif. */
    public record AnnounceRequest(UUID id, UUID subjectId, Integer chantier, String label) {
    }

    @GetMapping("/jarvis/works")
    public WorksResponse list() {
        UUID userId = currentUser.requireId();
        return new WorksResponse(service.list(userId).stream().map(WorkView::of).toList());
    }

    @PostMapping("/jarvis/works")
    public ResponseEntity<Void> announce(@RequestBody AnnounceRequest request) {
        UUID userId = currentUser.requireId();
        if (request == null || request.id() == null || request.subjectId() == null) {
            return ResponseEntity.badRequest().build();
        }
        String label = request.label() == null ? null
                : request.label().length() <= MAX_LABEL ? request.label() : request.label().substring(0, MAX_LABEL);
        return switch (service.announce(userId, request.id(), request.subjectId(), request.chantier(), label)) {
            case CREATED -> ResponseEntity.status(HttpStatus.CREATED).build();
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).build();
            case FAILED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        };
    }
}
