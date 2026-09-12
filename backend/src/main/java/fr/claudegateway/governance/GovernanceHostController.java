package fr.claudegateway.governance;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.governance.dto.GovernanceDepositPlan;
import fr.claudegateway.governance.dto.GovernanceFileComparison;
import fr.claudegateway.governance.dto.GovernanceHostSummary;
import fr.claudegateway.governance.dto.GovernanceHostView;

/**
 * La gouvernance <b>d'un poste</b> (F-75 / SF-75-01) : ce qui s'y applique, et les gestes qui
 * l'allument ou l'éteignent.
 *
 * <p>C'est le déplacement de grain de F-75. F-51 activait par <b>projet</b> ; on active désormais
 * <b>une fois sur un poste</b>, et tout dossier ajouté demain sous sa racine en hérite. <b>Aucune
 * dérogation par dossier</b> (tranché par le PO) : il n'existe plus aucune route qui activerait
 * quoi que ce soit sur un projet.</p>
 *
 * <p>Le poste se désigne par son identifiant, ou par le mot réservé
 * {@value GovernanceHostRef#HOSTED_REF} pour le poste « Hébergé » de F-71 — qui reste, lui, sans
 * identifiant public.</p>
 *
 * <p><b>Isolation.</b> Le poste est vérifié comme possédé avant toute lecture et toute écriture ; un
 * poste qui n'est pas le sien rend « introuvable », jamais « interdit » — un 403 apprendrait à
 * l'appelant qu'un poste existe sous cet identifiant.</p>
 */
@RestController
@RequestMapping("/governance/hosts")
public class GovernanceHostController {

    private final GovernanceActivationService activationService;
    private final GovernanceDepositService depositService;
    private final GovernanceFileReadingService fileReadingService;
    private final GovernanceHostScope hostScope;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public GovernanceHostController(GovernanceActivationService activationService,
            GovernanceDepositService depositService,
            GovernanceFileReadingService fileReadingService, GovernanceHostScope hostScope,
            AtelierAccessService atelierAccess, CurrentUser currentUser) {
        this.activationService = activationService;
        this.depositService = depositService;
        this.fileReadingService = fileReadingService;
        this.hostScope = hostScope;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    /** Mes postes gouvernables : mes machines, puis « Hébergé » s'il porte des dossiers. */
    @GetMapping
    public List<GovernanceHostSummary> hosts() {
        atelierAccess.requireAccess();
        return activationService.hosts(currentUser.requireId());
    }

    /** Ce qui s'applique à ce poste, ce qui pourrait s'y appliquer, et les dossiers concernés. */
    @GetMapping("/{hostRef}")
    public GovernanceHostView describe(@PathVariable String hostRef) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        return activationService.describe(userId, hostScope.require(userId, hostRef));
    }

    /**
     * Ce que ce paquet écrirait sur les dossiers de ce poste, et où. <b>N'écrit rien</b>.
     *
     * <p>C'est l'exigence de la feature : un paquet écrit sur la machine de l'utilisateur, l'écran
     * l'annonce donc avant.</p>
     */
    @GetMapping("/{hostRef}/{packageId}/preview")
    public GovernanceDepositPlan preview(@PathVariable String hostRef,
            @PathVariable UUID packageId) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        return depositService.plan(userId, hostScope.require(userId, hostRef), packageId);
    }

    /**
     * <b>Lire avant d'accepter</b> (F-75 / SF-75-02) : le contenu exact d'un fichier que le paquet
     * déposerait, et ce que chaque dossier du poste porte déjà sous ce chemin.
     *
     * <p>Sans cela, on approuve un dépôt de fichiers à l'aveugle sur la machine d'un client. Et
     * comme le dépôt n'écrase jamais, c'est l'<b>existant</b> qui restera quand il y en a un : le
     * voir est la seule façon de savoir ce qu'on accepte.</p>
     *
     * <p>Seuls les chemins <b>apportés par le paquet</b> sont lisibles ici. C'est une lecture de
     * gouvernance, pas un explorateur de fichiers.</p>
     */
    @GetMapping("/{hostRef}/{packageId}/file")
    public GovernanceFileComparison file(@PathVariable String hostRef, @PathVariable UUID packageId,
            @RequestParam(name = "path", required = false) String path) {
        atelierAccess.requireAccess();
        if (path == null || path.isBlank()) {
            throw new InvalidGovernancePackageException("Chemin de fichier requis.");
        }
        UUID userId = currentUser.requireId();
        return fileReadingService.read(userId, hostScope.require(userId, hostRef), packageId, path);
    }

    /**
     * Active un paquet retenu sur ce poste, et dépose ses fichiers dans chacun de ses dossiers.
     *
     * <p>Le dépôt crée ce qui manque et ne remplace jamais rien. S'il ne peut pas aboutir — machine
     * éteinte —, l'activation reste en attente : le paquet est bel et bien actif, seuls ses fichiers
     * attendent.</p>
     */
    @PostMapping("/{hostRef}/{packageId}")
    public GovernanceHostView activate(@PathVariable String hostRef, @PathVariable UUID packageId) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        GovernanceHostRef host = hostScope.require(userId, hostRef);
        activationService.activate(userId, host, packageId);
        depositService.deposit(userId, host, packageId);
        return activationService.describe(userId, host);
    }

    /**
     * Rejoue le dépôt : le geste offert quand la machine était éteinte, ou quand le paquet a été
     * republié depuis. Crée seulement ce qui manque.
     */
    @PostMapping("/{hostRef}/{packageId}/apply")
    public GovernanceDepositPlan apply(@PathVariable String hostRef, @PathVariable UUID packageId) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        return depositService.deposit(userId, hostScope.require(userId, hostRef), packageId);
    }

    /** Désactive un paquet sur ce poste. Les fichiers déjà déposés restent (décision D4). */
    @DeleteMapping("/{hostRef}/{packageId}")
    public ResponseEntity<Void> deactivate(@PathVariable String hostRef,
            @PathVariable UUID packageId) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        activationService.deactivate(userId, hostScope.require(userId, hostRef), packageId);
        return ResponseEntity.noContent().build();
    }
}
