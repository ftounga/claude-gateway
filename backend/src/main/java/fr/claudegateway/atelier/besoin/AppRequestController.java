package fr.claudegateway.atelier.besoin;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>La carte « Enregistrer ce besoin ? » d'un terminal</b> (F-205 / SF-205-01) : relire le statut,
 * [Enregistrer], [Non]. L'identité vient <b>exclusivement</b> du {@link CurrentUser} ; le service
 * applique {@code requireOwned} (404 sur un terminal d'autrui) puis relit le besoin par
 * {@code id + user_id + workspace_id}.
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/app-requests/{requestId}")
public class AppRequestController {

    private final AppRequestService service;
    private final CurrentUser currentUser;

    public AppRequestController(AppRequestService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public AppRequestView get(@PathVariable UUID workspaceId, @PathVariable UUID requestId) {
        return service.get(currentUser.requireId(), workspaceId, requestId);
    }

    @PostMapping("/record")
    public AppRequestView record(@PathVariable UUID workspaceId, @PathVariable UUID requestId) {
        return service.record(currentUser.requireId(), workspaceId, requestId);
    }

    @PostMapping("/dismiss")
    public AppRequestView dismiss(@PathVariable UUID workspaceId, @PathVariable UUID requestId) {
        return service.dismiss(currentUser.requireId(), workspaceId, requestId);
    }
}
