package fr.claudegateway.runner.rupture;

import java.time.OffsetDateTime;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Le journal des ruptures</b> (F-161 / SF-161-03) — <b>administrateur uniquement</b>.
 *
 * <p>La garde est la <b>définition unique</b> ({@link AdminService#assertAdmin()}) : un
 * non-administrateur reçoit <b>403</b>, jamais un rapport vide qui lui laisserait croire que son
 * poste n'a jamais décroché. L'isolation {@code user_id} s'applique <b>en plus</b> — la garde dit
 * « vous avez le droit de voir des ruptures », elle ne dit pas « lesquelles ».</p>
 */
@RestController
@RequestMapping("/admin/runner-disconnects")
public class RunnerDisconnectController {

    /** Fenêtre par défaut : assez large pour voir un motif, assez courte pour rester lisible. */
    private static final int DEFAULT_DAYS = 7;

    private final RunnerDisconnectService service;
    private final AdminService adminService;
    private final CurrentUser currentUser;

    public RunnerDisconnectController(RunnerDisconnectService service, AdminService adminService,
                                      CurrentUser currentUser) {
        this.service = service;
        this.adminService = adminService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public RunnerDisconnectReport report(@RequestParam(required = false) Integer days) {
        adminService.assertAdmin();
        int window = days == null || days <= 0 ? DEFAULT_DAYS : Math.min(days, 90);
        OffsetDateTime to = OffsetDateTime.now();
        return service.report(currentUser.requireId(), to.minusDays(window), to);
    }
}
