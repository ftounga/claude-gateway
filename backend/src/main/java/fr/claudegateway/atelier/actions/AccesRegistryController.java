package fr.claudegateway.atelier.actions;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * Le registre des accès vu d'un terminal (F-180 / SF-180-04). L'identité vient du {@link CurrentUser} ; la
 * portée est le poste du terminal <b>possédé</b>. Aucun secret n'entre : le service le refuse (400).
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/acces")
public class AccesRegistryController {

    private final AccesRegistryService service;
    private final CurrentUser currentUser;

    public AccesRegistryController(AccesRegistryService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<AccesRegistryService.AccesView> list(@PathVariable UUID workspaceId) {
        return service.list(currentUser.requireId(), workspaceId);
    }

    @PostMapping
    public AccesRegistryService.AccesView create(@PathVariable UUID workspaceId,
                                                 @RequestBody AccesRegistryService.AccesRequest request) {
        return service.create(currentUser.requireId(), workspaceId, request);
    }

    @PatchMapping("/{id}")
    public AccesRegistryService.AccesView update(@PathVariable UUID workspaceId, @PathVariable UUID id,
                                                 @RequestBody AccesRegistryService.AccesRequest request) {
        return service.update(currentUser.requireId(), workspaceId, id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID workspaceId, @PathVariable UUID id) {
        service.delete(currentUser.requireId(), workspaceId, id);
    }

    /** Les attentes ACCES faites à proposer au registre. */
    @GetMapping("/proposals")
    public List<AccesRegistryService.AccesProposal> proposals(@PathVariable UUID workspaceId) {
        return service.proposals(currentUser.requireId(), workspaceId);
    }

    /** « Pas besoin » : l'attente n'est plus proposée. */
    @PostMapping("/proposals/{actionId}/dismiss")
    public void dismiss(@PathVariable UUID workspaceId, @PathVariable UUID actionId) {
        service.dismissProposal(currentUser.requireId(), workspaceId, actionId);
    }
}
