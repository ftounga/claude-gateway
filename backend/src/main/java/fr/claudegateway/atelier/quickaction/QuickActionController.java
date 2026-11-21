package fr.claudegateway.atelier.quickaction;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.quickaction.QuickActionDtos.LaunchRequest;
import fr.claudegateway.atelier.quickaction.QuickActionDtos.LaunchView;
import fr.claudegateway.atelier.quickaction.QuickActionDtos.QuickActionRequest;
import fr.claudegateway.atelier.quickaction.QuickActionDtos.QuickActionView;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Les actions rapides d'un terminal</b> (F-275 / SF-275-01). Aucun tour modèle : la liste, le geste et son
 * lancement. Identité par {@link CurrentUser} ; {@code requireOwned} dans le service (404 sur un terminal d'autrui).
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/quick-actions")
public class QuickActionController {

    private final QuickActionService service;
    private final CurrentUser currentUser;

    public QuickActionController(QuickActionService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<QuickActionView> list(@PathVariable UUID workspaceId,
            @RequestParam(name = "skills", defaultValue = "true") boolean skills) {
        return service.list(currentUser.requireId(), workspaceId, skills);
    }

    @PostMapping
    public ResponseEntity<QuickActionView> create(@PathVariable UUID workspaceId,
            @RequestBody QuickActionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(currentUser.requireId(), workspaceId, request));
    }

    @PutMapping("/{actionId}")
    public QuickActionView update(@PathVariable UUID workspaceId, @PathVariable UUID actionId,
            @RequestBody QuickActionRequest request) {
        return service.update(currentUser.requireId(), workspaceId, actionId, request);
    }

    @DeleteMapping("/{actionId}")
    public ResponseEntity<Void> delete(@PathVariable UUID workspaceId, @PathVariable UUID actionId) {
        service.delete(currentUser.requireId(), workspaceId, actionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/launches")
    public LaunchView launched(@PathVariable UUID workspaceId, @RequestBody LaunchRequest request) {
        return service.recordLaunch(currentUser.requireId(), workspaceId, request == null ? null : request.key());
    }
}
