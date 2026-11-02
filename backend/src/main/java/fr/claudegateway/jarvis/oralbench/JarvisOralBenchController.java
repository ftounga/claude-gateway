package fr.claudegateway.jarvis.oralbench;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.shared.error.ErrorResponse;

/**
 * <b>Le banc de la parole</b> (F-240 / SF-240-02, D12) — <b>administrateur uniquement</b> (403 sinon), sur ses propres
 * sujets. {@code POST /api/admin/jarvis/oral-bench} lance un passage en tâche de fond (202 + identifiant) ;
 * {@code GET …/oral-bench/{runId}} rend son rapport (404 pour le passage d'un autre compte).
 */
@RestController
public class JarvisOralBenchController {

    private final JarvisOralBenchService service;
    private final AdminService adminService;
    private final CurrentUser currentUser;

    public JarvisOralBenchController(JarvisOralBenchService service, AdminService adminService,
            CurrentUser currentUser) {
        this.service = service;
        this.adminService = adminService;
        this.currentUser = currentUser;
    }

    /** {@code workspaceId} : le sujet dont la consigne système est prise (facultatif : le plus récent). */
    public record BenchRequest(UUID workspaceId) {
    }

    @PostMapping("/admin/jarvis/oral-bench")
    public ResponseEntity<?> start(@RequestBody(required = false) BenchRequest body) {
        adminService.assertAdmin();
        try {
            UUID runId = service.start(currentUser.requireId(), body == null ? null : body.workspaceId());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("runId", runId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("validation_error", e.getMessage()));
        }
    }

    @GetMapping("/admin/jarvis/oral-bench/{runId}")
    public ResponseEntity<JarvisOralBenchService.Report> report(@PathVariable UUID runId) {
        adminService.assertAdmin();
        return service.report(currentUser.requireId(), runId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
