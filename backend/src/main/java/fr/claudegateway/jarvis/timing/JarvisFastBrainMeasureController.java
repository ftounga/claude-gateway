package fr.claudegateway.jarvis.timing;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>La mesure du cerveau rapide</b> (F-235 / SF-235-07, D14) — {@code GET /api/admin/jarvis/fast-brain/measure},
 * <b>administrateur uniquement</b> (403 sinon), sur ses propres tours.
 */
@RestController
public class JarvisFastBrainMeasureController {

    static final int DEFAULT_DAYS = 7;

    private final JarvisFastBrainMeasureService service;
    private final AdminService adminService;
    private final CurrentUser currentUser;

    public JarvisFastBrainMeasureController(JarvisFastBrainMeasureService service, AdminService adminService,
            CurrentUser currentUser) {
        this.service = service;
        this.adminService = adminService;
        this.currentUser = currentUser;
    }

    @GetMapping("/admin/jarvis/fast-brain/measure")
    public JarvisFastBrainMeasureService.Measure measure(@RequestParam(required = false) Integer days) {
        adminService.assertAdmin();
        return service.measure(currentUser.requireId(), days == null || days <= 0 ? DEFAULT_DAYS : days);
    }
}
