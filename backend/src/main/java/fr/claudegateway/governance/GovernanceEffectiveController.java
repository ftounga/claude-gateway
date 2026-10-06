package fr.claudegateway.governance;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.governance.dto.GovernanceEffectiveView;

/**
 * <b>Ce qui s'applique vraiment sur un poste</b> (F-177 / SF-177-04). Lecture seule ; même garde que
 * l'écran Gouvernance (accès Forge), poste résolu possédé (introuvable sinon).
 */
@RestController
@RequestMapping("/governance/hosts")
public class GovernanceEffectiveController {

    private final GovernanceEffectiveService service;
    private final GovernanceHostScope hostScope;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public GovernanceEffectiveController(GovernanceEffectiveService service, GovernanceHostScope hostScope,
            AtelierAccessService atelierAccess, CurrentUser currentUser) {
        this.service = service;
        this.hostScope = hostScope;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    @GetMapping("/{hostRef}/effective")
    public GovernanceEffectiveView effective(@PathVariable String hostRef) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        return service.describe(userId, hostScope.require(userId, hostRef));
    }
}
