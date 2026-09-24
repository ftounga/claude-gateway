package fr.claudegateway.bilan;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Les bilans de session</b> (F-155 / SF-155-04) — <b>administrateur uniquement</b>.
 *
 * <p>La garde est la <b>définition unique</b> ({@link AdminService#assertAdmin()}), qui accepte
 * aussi le super-admin par e-mail. Un non-administrateur reçoit <b>403</b>, jamais une liste vide
 * qui lui laisserait croire qu'il n'y a rien.</p>
 *
 * <p>L'isolation {@code user_id} est appliquée en plus, par le service : la garde dit « vous avez
 * le droit de voir des bilans », elle ne dit pas « lesquels ».</p>
 */
@RestController
@RequestMapping("/admin/bilans")
public class SessionBilanController {

    private final SessionBilanService service;
    private final AdminService adminService;
    private final CurrentUser currentUser;

    public SessionBilanController(SessionBilanService service, AdminService adminService,
                                  CurrentUser currentUser) {
        this.service = service;
        this.adminService = adminService;
        this.currentUser = currentUser;
    }

    /** Les bilans du compte, les plus récents d'abord. */
    @GetMapping
    public List<SessionBilanView> list() {
        adminService.assertAdmin();
        return service.list(currentUser.requireId());
    }

    /** Un bilan ouvert : ses chiffres, son relevé, ses suggestions. */
    @GetMapping("/{id}")
    public SessionBilanDetail open(@PathVariable UUID id) {
        adminService.assertAdmin();
        return service.open(currentUser.requireId(), id);
    }

    /**
     * Produit le bilan de la session en cours d'un projet — le « proposé d'un clic ».
     *
     * @return {@code 200} avec le bilan gardé, ou {@code 204} quand il n'y avait rien à dire
     */
    @PostMapping
    public ResponseEntity<SessionBilanView> produce(@RequestParam UUID workspaceId) {
        adminService.assertAdmin();
        return service.produce(currentUser.requireId(), workspaceId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NO_CONTENT).build());
    }
}
