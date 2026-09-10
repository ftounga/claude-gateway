package fr.claudegateway.governance;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.governance.dto.GovernanceControlView;
import fr.claudegateway.governance.dto.GovernancePackageAdminView;
import fr.claudegateway.governance.dto.GovernancePackageRequest;

/**
 * Rédaction et publication des paquets de gouvernance (F-51 / SF-51-01).
 *
 * <p>Réservé à l'<b>admin</b> : l'autorisation est appliquée dans {@link GovernancePackageService}
 * par la garde unique du produit ({@code AdminService.assertAdmin()}, F-20), et la chaîne de sécurité
 * exige déjà un JWT valide. Le préfixe {@code /admin} est celui de l'administration existante, pour
 * qu'il n'y ait qu'un seul endroit à regarder quand on se demande ce qu'un admin peut faire.</p>
 */
@RestController
@RequestMapping("/admin/governance")
public class GovernanceAdminController {

    private final GovernancePackageService service;

    public GovernanceAdminController(GovernancePackageService service) {
        this.service = service;
    }

    /** Tous les paquets, brouillons compris. */
    @GetMapping("/packages")
    public List<GovernancePackageAdminView> list() {
        return service.listForAdmin();
    }

    /** Les contrôles que le serveur fournit — la seule liste qu'un paquet a le droit de citer. */
    @GetMapping("/controls")
    public List<GovernanceControlView> controls() {
        return service.listControls();
    }

    /** Crée un paquet, non publié, en version 1. */
    @PostMapping("/packages")
    public GovernancePackageAdminView create(@RequestBody GovernancePackageRequest request) {
        return service.create(request);
    }

    /** Remplace intégralement le contenu d'un paquet et incrémente sa version. */
    @PutMapping("/packages/{id}")
    public GovernancePackageAdminView update(@PathVariable UUID id,
            @RequestBody GovernancePackageRequest request) {
        return service.update(id, request);
    }

    /** Publie le paquet : il entre dans le catalogue de tous. */
    @PostMapping("/packages/{id}/publish")
    public GovernancePackageAdminView publish(@PathVariable UUID id) {
        return service.setPublished(id, true);
    }

    /** Dépublie : le paquet quitte le catalogue, sans toucher aux projets qui l'appliquent déjà. */
    @PostMapping("/packages/{id}/unpublish")
    public GovernancePackageAdminView unpublish(@PathVariable UUID id) {
        return service.setPublished(id, false);
    }

    /** Supprime un paquet non publié. */
    @DeleteMapping("/packages/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
