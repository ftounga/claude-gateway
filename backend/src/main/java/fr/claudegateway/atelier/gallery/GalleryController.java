package fr.claudegateway.atelier.gallery;

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
 * <b>L'aperçu d'une image de galerie</b> (F-278 / SF-278-01), servi au propriétaire du terminal.
 *
 * <p>Isolation double : le terminal est relu par {@code requireOwned} (404 si inconnu ou étranger), puis la clé de
 * stockage est reconstruite depuis l'utilisateur authentifié — une image d'un autre compte est introuvable.</p>
 */
@RestController
@RequestMapping("/workspaces")
public class GalleryController {

    private final WorkspaceService workspaceService;
    private final GalleryService gallery;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public GalleryController(WorkspaceService workspaceService, GalleryService gallery,
            AtelierAccessService atelierAccess, CurrentUser currentUser) {
        this.workspaceService = workspaceService;
        this.gallery = gallery;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    /** L'aperçu JPEG d'une image de ce terminal, ou {@code 404}. */
    @GetMapping("/{id}/gallery/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable UUID id, @PathVariable String imageId) {
        atelierAccess.requireTerminalAccess(id);
        UUID userId = currentUser.requireId();
        Workspace workspace = workspaceService.requireOwned(userId, id);
        return gallery.image(userId, workspace.getId(), imageId)
                .map(jpeg -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        // Un aperçu ne change jamais ; privé : il montre le poste.
                        .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePrivate())
                        .header("X-Content-Type-Options", "nosniff")
                        .body(jpeg))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
