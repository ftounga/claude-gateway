package fr.claudegateway.jarvis.replay;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.shared.error.ErrorResponse;

/**
 * <b>Le rejeu de séance Jarvis</b> (F-255 / SF-255-02) — <b>administrateur uniquement</b> (403 sinon), sur ses propres
 * séances. {@code GET /api/admin/jarvis/replay/sessions?days=7} : les séances et leur bilan, les plus récentes d'abord ;
 * {@code GET /api/admin/jarvis/replay?from=…&to=…} (ISO-8601) : une séance tour par tour, verdicts et bilan. Même
 * rejeu pour la page d'administration et pour Claude Code.
 */
@RestController
public class JarvisReplayController {

    static final int DEFAULT_DAYS = 7;

    private final JarvisReplayService service;
    private final AdminService adminService;
    private final CurrentUser currentUser;

    public JarvisReplayController(JarvisReplayService service, AdminService adminService, CurrentUser currentUser) {
        this.service = service;
        this.adminService = adminService;
        this.currentUser = currentUser;
    }

    @GetMapping("/admin/jarvis/replay/sessions")
    public List<JarvisReplay.Session> sessions(@RequestParam(required = false) Integer days) {
        adminService.assertAdmin();
        return service.sessions(currentUser.requireId(), days == null || days <= 0 ? DEFAULT_DAYS : days);
    }

    @GetMapping("/admin/jarvis/replay")
    public ResponseEntity<?> replay(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        adminService.assertAdmin();
        try {
            return ResponseEntity.ok(service.replay(currentUser.requireId(), parse(from), parse(to)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("validation_error",
                    "Fenêtre de séance invalide (from et to ISO-8601, au plus 6 h)."));
        }
    }

    private static OffsetDateTime parse(String value) {
        return value == null || value.isBlank() ? null : OffsetDateTime.parse(value.strip());
    }
}
