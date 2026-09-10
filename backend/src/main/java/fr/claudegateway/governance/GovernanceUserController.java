package fr.claudegateway.governance;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.governance.dto.GovernanceSelectionRequest;
import fr.claudegateway.governance.dto.GovernanceSelectionView;

/**
 * Mon <b>catalogue personnel</b> de gouvernance (F-51 / SF-51-02).
 *
 * <p><b>Retenir n'active rien.</b> C'est un geste de bibliothèque : il range le paquet chez moi et
 * m'ouvre le droit de l'activer sur mes projets ({@link GovernanceProjectController}). La seule chose
 * qui agisse toute seule est le drapeau « appliqué par défaut », et seulement sur <b>mes</b> projets
 * à venir.</p>
 *
 * <p>L'identité vient du {@link CurrentUser}, jamais d'un paramètre, et l'accès Atelier est exigé
 * comme sur {@code /atelier/**} et {@code /runner-hosts/**} : la gouvernance s'applique à des
 * projets, elle ne s'ouvre pas à qui n'y a pas droit.</p>
 */
@RestController
@RequestMapping("/governance/selection")
public class GovernanceUserController {

    private final GovernanceSelectionService selectionService;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public GovernanceUserController(GovernanceSelectionService selectionService,
            AtelierAccessService atelierAccess, CurrentUser currentUser) {
        this.selectionService = selectionService;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    /** Mon catalogue personnel. */
    @GetMapping
    public List<GovernanceSelectionView> selection() {
        atelierAccess.requireAccess();
        return selectionService.list(currentUser.requireId());
    }

    /** Retient un paquet publié, ou met à jour son drapeau « appliqué par défaut ». */
    @PutMapping("/{packageId}")
    public List<GovernanceSelectionView> select(@PathVariable UUID packageId,
            @RequestBody(required = false) GovernanceSelectionRequest request) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        selectionService.select(userId, packageId,
                request != null && request.defaultAppliedOrFalse());
        return selectionService.list(userId);
    }

    /** Retire un paquet de mon catalogue. Les projets où il est actif ne changent pas (A2). */
    @DeleteMapping("/{packageId}")
    public ResponseEntity<Void> deselect(@PathVariable UUID packageId) {
        atelierAccess.requireAccess();
        selectionService.deselect(currentUser.requireId(), packageId);
        return ResponseEntity.noContent().build();
    }
}
