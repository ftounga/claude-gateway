package fr.claudegateway.jarvis.fastbrain;

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
 * <b>Le banc du cerveau rapide</b> (F-235 / SF-235-08, D16) — <b>administrateur uniquement</b> (403 sinon), sur ses
 * propres demandes. {@code POST /api/admin/jarvis/fast-brain/bench} lance un passage en tâche de fond (202 +
 * identifiant) ; {@code GET …/bench/{runId}} rend son rapport (404 pour le passage d'un autre compte).
 */
@RestController
public class JarvisFastBrainBenchController {

    private final JarvisFastBrainBenchService service;
    private final AdminService adminService;
    private final CurrentUser currentUser;

    public JarvisFastBrainBenchController(JarvisFastBrainBenchService service, AdminService adminService,
            CurrentUser currentUser) {
        this.service = service;
        this.adminService = adminService;
        this.currentUser = currentUser;
    }

    /** {@code set} : {@code REAL} (60 dernières demandes vocales, défaut) ou {@code PERSONALITY} (20 questions). */
    public record BenchRequest(String set, String model) {
    }

    @PostMapping("/admin/jarvis/fast-brain/bench")
    public ResponseEntity<?> start(@RequestBody(required = false) BenchRequest body) {
        adminService.assertAdmin();
        try {
            UUID runId = service.start(currentUser.requireId(), body == null ? null : body.set(),
                    body == null ? null : body.model());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("runId", runId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("validation_error", e.getMessage()));
        }
    }

    @GetMapping("/admin/jarvis/fast-brain/bench/{runId}")
    public ResponseEntity<JarvisFastBrainBenchService.Report> report(@PathVariable UUID runId) {
        adminService.assertAdmin();
        return service.report(currentUser.requireId(), runId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
