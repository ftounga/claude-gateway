package fr.claudegateway.atelier.situation;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Le point de situation</b> (F-225 / SF-225-01). L'identité vient du {@link CurrentUser} ; l'isolation est dans le
 * service ({@code requireOwned} d'abord : 404 sur le terminal d'autrui). Aucune logique ici.
 */
@RestController
public class SituationController {

    private final SituationService service;
    private final CurrentUser currentUser;

    public SituationController(SituationService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/workspaces/{workspaceId}/situation")
    public Situation situation(@PathVariable UUID workspaceId) {
        return service.situation(currentUser.requireId(), workspaceId);
    }
}
