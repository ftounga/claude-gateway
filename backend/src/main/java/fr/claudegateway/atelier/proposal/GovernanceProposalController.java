package fr.claudegateway.atelier.proposal;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Les propositions de gouvernance d'un terminal</b> (F-177 / SF-177-02) : relire le statut,
 * [Appliquer], [Refuser].
 *
 * <p>L'identité vient <b>exclusivement</b> du {@link CurrentUser} ; le service applique
 * {@code requireOwned} (404 sur un terminal d'autrui) puis relit la proposition par
 * {@code id + user_id + workspace_id}.</p>
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/governance-proposals/{proposalId}")
public class GovernanceProposalController {

    private final GovernanceProposalService service;
    private final CurrentUser currentUser;

    public GovernanceProposalController(GovernanceProposalService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public GovernanceProposalView get(@PathVariable UUID workspaceId, @PathVariable UUID proposalId) {
        return service.get(currentUser.requireId(), workspaceId, proposalId);
    }

    @PostMapping("/apply")
    public GovernanceProposalView apply(@PathVariable UUID workspaceId, @PathVariable UUID proposalId) {
        return service.apply(currentUser.requireId(), workspaceId, proposalId);
    }

    @PostMapping("/refuse")
    public GovernanceProposalView refuse(@PathVariable UUID workspaceId, @PathVariable UUID proposalId) {
        return service.refuse(currentUser.requireId(), workspaceId, proposalId);
    }
}
