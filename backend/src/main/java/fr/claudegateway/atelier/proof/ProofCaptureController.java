package fr.claudegateway.atelier.proof;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>L'image d'une capture de preuve</b> (F-212 / SF-212-03), servie au terminal qui l'affiche.
 *
 * <p>Isolation double : le terminal est relu par {@code requireOwned} (404 si inconnu ou étranger), puis
 * la capture par {@code (id, user_id, workspace_id)} ; la clé de stockage est reconstruite depuis
 * l'utilisateur authentifié.</p>
 */
@RestController
@RequestMapping("/workspaces")
public class ProofCaptureController {

    private final WorkspaceService workspaceService;
    private final ProofCaptureService proofs;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public ProofCaptureController(WorkspaceService workspaceService, ProofCaptureService proofs,
            AtelierAccessService atelierAccess, CurrentUser currentUser) {
        this.workspaceService = workspaceService;
        this.proofs = proofs;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    /** L'image PNG d'une capture de ce terminal, ou {@code 404}. */
    @GetMapping("/{id}/proofs/{captureId}")
    public ResponseEntity<byte[]> capture(@PathVariable UUID id, @PathVariable String captureId) {
        atelierAccess.requireTerminalAccess(id);
        UUID userId = currentUser.requireId();
        Workspace workspace = workspaceService.requireOwned(userId, id);
        return proofs.image(userId, workspace.getId(), captureId)
                .map(png -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        // Une capture ne change jamais ; privée : elle montre le terminal du poste.
                        .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePrivate())
                        .body(png))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
