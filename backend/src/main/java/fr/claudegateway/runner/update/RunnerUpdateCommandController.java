package fr.claudegateway.runner.update;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessDeniedException;
import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.AuthenticatedUser;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.user.UserRole;

/**
 * « Mettre à jour » le runner d'un poste (F-111 / SF-111-04) et lire le journal de ses mises à jour.
 *
 * <p>Routes <b>JWT</b>, sous {@code /runner-hosts/**} comme la gestion des postes. Propriétaire avec le
 * droit runner (Forge ou Vigie), ou <b>ADMIN</b> — qui a tous les droits, y compris sur le poste d'autrui.
 * L'autorisation fine (propriétaire ou ADMIN) est tenue par le service.</p>
 */
@RestController
@RequestMapping("/runner-hosts")
public class RunnerUpdateCommandController {

    /** Corps de la demande : {@code force} relance une mise à jour active sans attendre le calme. */
    public record RunnerUpdateRequest(Boolean force) {
    }

    private final RunnerUpdateService updateService;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public RunnerUpdateCommandController(RunnerUpdateService updateService, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.updateService = updateService;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    @PostMapping("/{hostId}/runner-update")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunnerUpdateProgress request(@PathVariable UUID hostId,
            @RequestBody(required = false) RunnerUpdateRequest request) {
        AuthenticatedUser caller = caller();
        return updateService.request(caller, hostId, request != null && Boolean.TRUE.equals(request.force()));
    }

    @GetMapping("/{hostId}/runner-update/journal")
    public List<RunnerUpdateProgress> journal(@PathVariable UUID hostId) {
        return updateService.journal(caller(), hostId);
    }

    private AuthenticatedUser caller() {
        AuthenticatedUser caller = currentUser.principal().orElseThrow(AtelierAccessDeniedException::new);
        if (caller.role() != UserRole.ADMIN) {
            atelierAccess.requireRunnerAccess();
        }
        return caller;
    }
}
