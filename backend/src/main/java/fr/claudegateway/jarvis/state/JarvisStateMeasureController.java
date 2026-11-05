package fr.claudegateway.jarvis.state;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>La mesure du registre d'état</b> (F-247 / SF-247-01) — {@code GET /api/admin/jarvis/state/measure},
 * <b>administrateur uniquement</b> (403 sinon), sur ses propres données.
 */
@RestController
public class JarvisStateMeasureController {

    static final int DEFAULT_DAYS = 7;

    private final JarvisStateMeasureService service;
    private final AdminService adminService;
    private final CurrentUser currentUser;

    public JarvisStateMeasureController(JarvisStateMeasureService service, AdminService adminService,
            CurrentUser currentUser) {
        this.service = service;
        this.adminService = adminService;
        this.currentUser = currentUser;
    }

    @GetMapping("/admin/jarvis/state/measure")
    public JarvisStateMeasureService.Measure measure(@RequestParam(required = false) Integer days) {
        adminService.assertAdmin();
        return service.measure(currentUser.requireId(), days == null || days <= 0 ? DEFAULT_DAYS : days);
    }
}
