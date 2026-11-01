package fr.claudegateway.atelier.veille;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>La mesure d'adoption des veilles</b> (F-230 / SF-230-04, D8) — <b>administrateur uniquement</b> (403 sinon),
 * sur ses propres veilles (isolation {@code user_id}, même patron que la mesure du parcours F-176).
 */
@RestController
@RequestMapping("/admin/jarvis/watches")
public class JarvisWatchMeasureController {

    static final int DEFAULT_DAYS = 7;

    private final JarvisWatchMeasureService service;
    private final AdminService adminService;
    private final CurrentUser currentUser;

    public JarvisWatchMeasureController(JarvisWatchMeasureService service, AdminService adminService,
            CurrentUser currentUser) {
        this.service = service;
        this.adminService = adminService;
        this.currentUser = currentUser;
    }

    @GetMapping("/measure")
    public JarvisWatchMeasureService.Measure measure(@RequestParam(required = false) Integer days) {
        adminService.assertAdmin();
        int window = days == null || days <= 0 ? DEFAULT_DAYS : Math.min(days, 90);
        return service.measure(currentUser.requireId(), window);
    }
}
