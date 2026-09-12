package fr.claudegateway.teams.block;

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
 * <b>L'image d'un moment</b> (F-89 / SF-89-02), servie au terminal qui l'affiche.
 *
 * <p>Un seul verbe, {@code GET}. Le dépôt n'a pas d'endpoint : les images sont produites sur la
 * machine et remontées par F-90 — ouvrir une route de dépôt sans producteur offrirait une surface
 * pour rien.</p>
 *
 * <p><b>L'isolation est double, et la seconde suffirait.</b> Le workspace est relu par
 * {@code requireOwned} (404 si inconnu <i>ou</i> à quelqu'un d'autre, indiscernables), puis la clé de
 * stockage est <b>reconstruite</b> depuis l'utilisateur authentifié : l'identifiant reçu du client
 * n'est qu'un dernier segment, jamais un chemin.</p>
 *
 * <p><b>Aucun droit Teams n'est exigé ici</b>, et c'est voulu : un compte qui a résilié l'option doit
 * continuer de <b>voir</b> les comptes rendus qu'il a payés. L'option ouvre la capacité de produire,
 * pas celle de relire.</p>
 */
@RestController
@RequestMapping("/workspaces")
public class TeamsMomentController {

    private final WorkspaceService workspaceService;
    private final TeamsMomentImageService images;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public TeamsMomentController(WorkspaceService workspaceService, TeamsMomentImageService images,
            AtelierAccessService atelierAccess, CurrentUser currentUser) {
        this.workspaceService = workspaceService;
        this.images = images;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    /** L'image d'un moment de ce terminal, ou {@code 404}. */
    @GetMapping("/{id}/teams/moments/{imageId}")
    public ResponseEntity<byte[]> moment(@PathVariable UUID id, @PathVariable String imageId) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        Workspace workspace = workspaceService.requireOwned(userId, id);
        return images.find(userId, workspace.getId(), imageId)
                .map(image -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(image.contentType()))
                        // Une image de moment ne change jamais : son identifiant est tiré au dépôt.
                        // Privée, parce qu'elle montre ce qui était à l'écran d'une réunion.
                        .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePrivate())
                        .body(image.content()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
