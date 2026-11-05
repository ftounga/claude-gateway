package fr.claudegateway.jarvis.cards;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>La mesure des pancartes</b> (F-248 / SF-248-01) — {@code GET /api/admin/jarvis/cards/measure},
 * <b>administrateur uniquement</b> (403 sinon), sur ses propres données.
 */
@RestController
public class JarvisCardsMeasureController {

    static final int DEFAULT_DAYS = 7;

    private final JarvisCardsMeasureService service;
    private final AdminService adminService;
    private final CurrentUser currentUser;

    public JarvisCardsMeasureController(JarvisCardsMeasureService service, AdminService adminService,
            CurrentUser currentUser) {
        this.service = service;
        this.adminService = adminService;
        this.currentUser = currentUser;
    }

    @GetMapping("/admin/jarvis/cards/measure")
    public JarvisCardsMeasureService.Measure measure(@RequestParam(required = false) Integer days) {
        adminService.assertAdmin();
        return service.measure(currentUser.requireId(), days == null || days <= 0 ? DEFAULT_DAYS : days);
    }
}
