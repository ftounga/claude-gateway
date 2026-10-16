package fr.claudegateway.atelier.actions;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * Les interlocuteurs du poste d'un terminal (F-180 / SF-180-03). L'identité vient du {@link CurrentUser} ;
 * le poste est celui du terminal <b>possédé</b> — jamais lu dans la requête.
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/interlocuteurs")
public class InterlocuteurController {

    private final InterlocuteurService service;
    private final CurrentUser currentUser;

    public InterlocuteurController(InterlocuteurService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<InterlocuteurService.InterlocuteurView> list(@PathVariable UUID workspaceId) {
        return service.list(currentUser.requireId(), workspaceId);
    }

    @PatchMapping("/{id}")
    public InterlocuteurService.InterlocuteurView update(@PathVariable UUID workspaceId, @PathVariable UUID id,
                                                         @RequestBody InterlocuteurService.UpdateRequest request) {
        return service.update(currentUser.requireId(), workspaceId, id, request);
    }

    @GetMapping("/merge-suggestions")
    public List<InterlocuteurService.MergeSuggestion> suggestions(@PathVariable UUID workspaceId) {
        return service.suggestions(currentUser.requireId(), workspaceId);
    }

    @PostMapping("/{id}/merge")
    public InterlocuteurService.InterlocuteurView merge(@PathVariable UUID workspaceId, @PathVariable UUID id,
                                                        @RequestBody MergeRequest request) {
        return service.merge(currentUser.requireId(), workspaceId, id, request == null ? null : request.intoId());
    }

    @PostMapping("/{id}/distinct")
    public void distinct(@PathVariable UUID workspaceId, @PathVariable UUID id,
                         @RequestBody DistinctRequest request) {
        service.distinct(currentUser.requireId(), workspaceId, id, request == null ? null : request.otherId());
    }

    /** L'interlocuteur à garder. */
    public record MergeRequest(UUID intoId) {
    }

    /** L'autre interlocuteur, qui est une autre personne. */
    public record DistinctRequest(UUID otherId) {
    }
}
