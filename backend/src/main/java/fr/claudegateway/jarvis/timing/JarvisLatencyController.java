package fr.claudegateway.jarvis.timing;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.shared.error.ErrorResponse;

/**
 * <b>Le chronomètre du tour vocal</b> (F-233 / SF-233-01, D2). L'écran Jarvis déclare la mesure d'un tour
 * ({@code POST /api/jarvis/timings}) ; l'administrateur lit p50/p90 sur <b>ses</b> tours
 * ({@code GET /api/admin/jarvis/latency}, 403 sinon — même patron que {@code /admin/jarvis/measure}).
 */
@RestController
public class JarvisLatencyController {

    static final int DEFAULT_DAYS = 7;

    private final JarvisLatencyService service;
    private final AtelierAccessService atelierAccess;
    private final AdminService adminService;
    private final CurrentUser currentUser;

    public JarvisLatencyController(JarvisLatencyService service, AtelierAccessService atelierAccess,
            AdminService adminService, CurrentUser currentUser) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.adminService = adminService;
        this.currentUser = currentUser;
    }

    @PostMapping("/jarvis/timings")
    public ResponseEntity<?> record(@RequestBody(required = false) JarvisLatencyService.TimingReport body) {
        atelierAccess.requireAccess();
        try {
            service.record(currentUser.requireId(), body);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("validation_error", e.getMessage()));
        }
    }

    @GetMapping("/admin/jarvis/latency")
    public JarvisLatencyService.Latency latency(@RequestParam(required = false) Integer days) {
        adminService.assertAdmin();
        return service.latency(currentUser.requireId(), days == null || days <= 0 ? DEFAULT_DAYS : days);
    }
}
