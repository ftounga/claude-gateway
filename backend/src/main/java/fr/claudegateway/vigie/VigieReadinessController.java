package fr.claudegateway.vigie;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.vigie.dto.VigieReadinessResponse;

/**
 * <b>La check-list de mise en service de la Vigie</b>, côté utilisateur (F-122 / SF-122-02).
 *
 * <p>L'identité vient du {@link CurrentUser}, jamais d'un paramètre ; la possession du poste est
 * vérifiée en aval ({@link VigieReadinessService#readiness}). Même gate que les endpoints frères de
 * {@code /runner-hosts} : {@link AtelierAccessService#requireRunnerAccess()}.</p>
 */
@RestController
@RequestMapping("/runner-hosts")
public class VigieReadinessController {

    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;
    private final VigieReadinessService readinessService;

    public VigieReadinessController(AtelierAccessService atelierAccess, CurrentUser currentUser,
            VigieReadinessService readinessService) {
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
        this.readinessService = readinessService;
    }

    /** La check-list vert/rouge du poste, à vérifier avant de « démarrer ». */
    @GetMapping("/{hostId}/vigie/readiness")
    public VigieReadinessResponse readiness(@PathVariable UUID hostId) {
        atelierAccess.requireRunnerAccess();
        UUID userId = currentUser.requireId();
        return readinessService.readiness(userId, hostId);
    }
}
