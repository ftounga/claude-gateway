package fr.claudegateway.governance;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.governance.dto.GovernanceDepositPlan;
import fr.claudegateway.governance.dto.GovernanceProjectView;

/**
 * La gouvernance <b>d'un projet</b> (F-51 / SF-51-02) : ce qui s'y applique, et les gestes qui
 * l'allument ou l'éteignent.
 *
 * <p>Le chemin suit celui du mode runner ({@code /workspaces/{id}/runner}) : la gouvernance est une
 * propriété du projet, pas un espace à part.</p>
 *
 * <p><b>Isolation.</b> Le projet est vérifié comme possédé par le service avant toute écriture ; un
 * projet qui n'est pas le sien rend « introuvable », jamais « interdit » — un 403 apprendrait à
 * l'appelant qu'un projet existe sous cet identifiant.</p>
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/governance")
public class GovernanceProjectController {

    private final GovernanceActivationService activationService;
    private final GovernanceDepositService depositService;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public GovernanceProjectController(GovernanceActivationService activationService,
            GovernanceDepositService depositService, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.activationService = activationService;
        this.depositService = depositService;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    /** Ce qui s'applique à ce projet, et ce qui pourrait s'y appliquer. */
    @GetMapping
    public GovernanceProjectView describe(@PathVariable UUID workspaceId) {
        atelierAccess.requireAccess();
        return activationService.describe(currentUser.requireId(), workspaceId);
    }

    /**
     * Ce que ce paquet écrirait sur ce projet, et où. <b>N'écrit rien</b> (F-51 / SF-51-03).
     *
     * <p>C'est l'exigence de la feature : un paquet écrit sur la machine de l'utilisateur, l'écran
     * l'annonce donc avant.</p>
     */
    @GetMapping("/{packageId}/preview")
    public GovernanceDepositPlan preview(@PathVariable UUID workspaceId,
            @PathVariable UUID packageId) {
        atelierAccess.requireAccess();
        return depositService.plan(currentUser.requireId(), workspaceId, packageId);
    }

    /**
     * Active un paquet retenu sur ce projet, et dépose ses fichiers dans la foulée.
     *
     * <p>Le dépôt crée ce qui manque et ne remplace jamais rien. S'il ne peut pas aboutir — machine
     * éteinte —, l'activation reste en attente : le paquet est bel et bien actif, seuls ses fichiers
     * attendent.</p>
     */
    @PostMapping("/{packageId}")
    public GovernanceProjectView activate(@PathVariable UUID workspaceId,
            @PathVariable UUID packageId) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        activationService.activate(userId, workspaceId, packageId);
        depositService.deposit(userId, workspaceId, packageId);
        return activationService.describe(userId, workspaceId);
    }

    /**
     * Rejoue le dépôt : le geste offert quand la machine était éteinte, ou quand le paquet a été
     * republié depuis. Crée seulement ce qui manque.
     */
    @PostMapping("/{packageId}/apply")
    public GovernanceDepositPlan apply(@PathVariable UUID workspaceId,
            @PathVariable UUID packageId) {
        atelierAccess.requireAccess();
        return depositService.deposit(currentUser.requireId(), workspaceId, packageId);
    }

    /** Désactive un paquet sur ce projet. Les fichiers déjà déposés restent (décision D4). */
    @DeleteMapping("/{packageId}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID workspaceId,
            @PathVariable UUID packageId) {
        atelierAccess.requireAccess();
        activationService.deactivate(currentUser.requireId(), workspaceId, packageId);
        return ResponseEntity.noContent().build();
    }
}
