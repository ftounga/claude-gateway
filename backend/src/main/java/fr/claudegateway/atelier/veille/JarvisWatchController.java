package fr.claudegateway.atelier.veille;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.veille.JarvisWatchDtos.JarvisWatchTestView;
import fr.claudegateway.atelier.veille.JarvisWatchDtos.JarvisWatchView;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Les veilles Jarvis d'un sujet</b> (F-230). L'identité vient <b>exclusivement</b> du {@link CurrentUser} ; le
 * service applique {@code requireOwned} puis relit la veille par {@code id + user_id + workspace_id}. Aucune
 * logique ici.
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/watches")
public class JarvisWatchController {

    private final JarvisWatchService service;
    private final CurrentUser currentUser;

    public JarvisWatchController(JarvisWatchService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<JarvisWatchView> list(@PathVariable UUID workspaceId) {
        return service.list(currentUser.requireId(), workspaceId);
    }

    /** Tester la sonde devant le PO : une exécution sur le poste (SF-230-01). */
    @PostMapping("/{watchId}/test")
    public JarvisWatchTestView test(@PathVariable UUID workspaceId, @PathVariable UUID watchId) {
        return service.test(currentUser.requireId(), workspaceId, watchId);
    }

    /** Le PO active une veille testée (SF-230-01). */
    @PostMapping("/{watchId}/accept")
    public JarvisWatchView accept(@PathVariable UUID workspaceId, @PathVariable UUID watchId) {
        return service.accept(currentUser.requireId(), workspaceId, watchId);
    }

    @DeleteMapping("/{watchId}")
    public ResponseEntity<Void> delete(@PathVariable UUID workspaceId, @PathVariable UUID watchId) {
        service.delete(currentUser.requireId(), workspaceId, watchId);
        return ResponseEntity.noContent().build();
    }
}
