package fr.claudegateway.runner.host;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.runner.RunnerKillSwitchService;
import fr.claudegateway.runner.RunnerPairingService;
import fr.claudegateway.runner.RunnerPairingService.PairingCode;
import fr.claudegateway.runner.RunnerStatusService;
import fr.claudegateway.runner.RunnerTokenService;
import fr.claudegateway.runner.browse.RunnerHostFolderBrowser;
import fr.claudegateway.runner.dto.PairingCodeResponse;
import fr.claudegateway.runner.dto.RunnerKillResponse;
import fr.claudegateway.runner.dto.RunnerStatusResponse;
import fr.claudegateway.runner.dto.RunnerTokenResponse;
import fr.claudegateway.runner.host.dto.HostFoldersResponse;
import fr.claudegateway.runner.host.dto.HostMissionRequest;
import fr.claudegateway.runner.host.dto.RunnerHostOverviewResponse;
import fr.claudegateway.runner.host.dto.RunnerHostRequest;
import fr.claudegateway.runner.host.dto.RunnerHostResponse;
import jakarta.validation.Valid;

/**
 * Gestion des <b>postes</b> d'un utilisateur (F-48 / SF-48-01) : créer une machine, l'appairer
 * <b>une seule fois</b>, voir son état, révoquer ses jetons, la couper.
 *
 * <p>Ces endpoints sont <b>JWT</b> (chaîne principale) et gardés par l'accès Atelier. Ils vivent
 * sous {@code /runner-hosts/**} — volontairement <b>hors</b> du préfixe {@code /runner/**}, qui est
 * la chaîne du protocole runner et refuse tout ce qui n'y est pas explicitement listé :
 * un jeton runner n'y donne aucun droit, et un JWT utilisateur n'ouvre aucun canal d'exécution.</p>
 *
 * <p>L'identité vient du {@link CurrentUser}, jamais d'un paramètre.</p>
 */
@RestController
@RequestMapping("/runner-hosts")
public class RunnerHostController {

    private final RunnerHostService hostService;
    private final RunnerHostOverviewService overviewService;
    private final RunnerPairingService pairingService;
    private final RunnerTokenService tokenService;
    private final RunnerStatusService statusService;
    private final RunnerKillSwitchService killSwitchService;
    private final WorkspaceService workspaceService;
    private final RunnerHostFolderBrowser folderBrowser;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public RunnerHostController(RunnerHostService hostService,
            RunnerHostOverviewService overviewService, RunnerPairingService pairingService,
            RunnerTokenService tokenService, RunnerStatusService statusService,
            RunnerKillSwitchService killSwitchService, WorkspaceService workspaceService,
            RunnerHostFolderBrowser folderBrowser, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.hostService = hostService;
        this.overviewService = overviewService;
        this.pairingService = pairingService;
        this.tokenService = tokenService;
        this.statusService = statusService;
        this.killSwitchService = killSwitchService;
        this.workspaceService = workspaceService;
        this.folderBrowser = folderBrowser;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    /** Crée un poste au nom libre. */
    @PostMapping
    public RunnerHostResponse create(@Valid @RequestBody RunnerHostRequest request) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        RunnerHost host = hostService.create(userId, request.name());
        return RunnerHostResponse.from(host, false);
    }

    /** Postes de l'utilisateur, avec leur état de connexion. */
    @GetMapping
    public List<RunnerHostResponse> list() {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        return hostService.list(userId).stream()
                .map(host -> RunnerHostResponse.from(host,
                        statusService.statusOf(userId, host).connected()))
                .toList();
    }

    /**
     * <b>Vue d'ensemble</b> (F-49 / SF-49-01) : tous les postes, leur état, les projets rangés
     * dessous et l'activité observée sur chacun — en un seul appel.
     *
     * <p>Déclarée avant {@code /{hostId}} par lisibilité ; Spring privilégie de toute façon le
     * chemin littéral sur la variable, et un test d'intégration le vérifie.</p>
     */
    @GetMapping("/overview")
    public List<RunnerHostOverviewResponse> overview() {
        atelierAccess.requireAccess();
        return overviewService.overview(currentUser.requireId());
    }

    /**
     * <b>Sous-dossiers du poste</b> (F-71 / SF-71-02) : ce qu'on <b>clique</b> pour désigner le
     * dossier d'un projet, au lieu de le taper.
     *
     * <p>Un chemin tapé à la main crée un projet vide qui n'échoue qu'au <b>premier usage</b>, quand
     * plus personne ne fait le lien avec la faute de frappe. Décision du PO : le runner liste, on
     * clique.</p>
     *
     * <p>Le runner doit être <b>connecté</b> : sans machine, il n'y a rien à lister, et la gateway
     * le <b>dit</b> (409) plutôt que de rendre une liste vide qui ferait croire à une racine sans
     * sous-dossier.</p>
     *
     * <p>Déclaré avant {@code /{hostId}} par lisibilité, comme {@code /overview}.</p>
     *
     * @param path chemin relatif sous la racine ; absent ou vide = la racine elle-même
     */
    @GetMapping("/{hostId}/folders")
    public HostFoldersResponse folders(@PathVariable UUID hostId,
            @RequestParam(name = "path", required = false) String path) {
        atelierAccess.requireAccess();
        return folderBrowser.folders(currentUser.requireId(), hostId, path);
    }

    /** Détail d'un poste possédé. */
    @GetMapping("/{hostId}")
    public RunnerHostResponse get(@PathVariable UUID hostId) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        RunnerHost host = hostService.requireOwned(userId, hostId);
        return RunnerHostResponse.from(host, statusService.statusOf(userId, host).connected());
    }

    /** Renomme un poste. */
    @PutMapping("/{hostId}")
    public RunnerHostResponse rename(@PathVariable UUID hostId,
            @Valid @RequestBody RunnerHostRequest request) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        RunnerHost host = hostService.rename(userId, hostId, request.name());
        return RunnerHostResponse.from(host, statusService.statusOf(userId, host).connected());
    }

    /**
     * Déclare l'<b>état de mission</b> du poste (F-60 / SF-60-01) : où en est le travail chez ce
     * client — {@code ACTIVE}, {@code PENDING}, {@code CLOSED}.
     *
     * <p>Chemin dédié plutôt qu'un champ de plus sur le renommage : un corps de renommage qui
     * n'enverrait pas l'état remettrait la mission « en cours » sans que personne l'ait demandé.
     * Deux gestes, deux chemins, aucune perte silencieuse.</p>
     *
     * <p><b>Ce geste ne coupe rien</b> : aucun jeton révoqué, aucune liaison fermée, aucun projet
     * détaché, aucun journal effacé. Clôturer une mission range un poste, elle ne l'éteint pas —
     * le coupe-circuit reste {@code POST /{hostId}/kill}.</p>
     */
    @PutMapping("/{hostId}/mission")
    public RunnerHostResponse setMissionStatus(@PathVariable UUID hostId,
            @Valid @RequestBody HostMissionRequest request) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        RunnerHost host = hostService.setMissionStatus(userId, hostId, request.missionStatus());
        return RunnerHostResponse.from(host, statusService.statusOf(userId, host).connected());
    }

    /**
     * Supprime un poste <b>vide</b> : coupe sa liaison, efface ses jetons et ses codes, puis la
     * ligne du poste.
     *
     * <p><b>Refusé tant qu'il reste des projets</b> (F-69 / SF-69-01), décision du PO : pas de
     * cascade. Le refus est <b>total</b> — il précède le coupe-circuit, donc aucun jeton n'est
     * révoqué, aucune liaison n'est coupée, aucun projet n'est touché. Un refus qui aurait déjà
     * débranché la machine ne serait pas un refus.</p>
     *
     * <p>Avant F-69, ce chemin <b>détachait</b> les projets : ils survivaient, sans machine, sans
     * que personne l'ait demandé. Ni cascade ni refus — une troisième voie, qu'on retire ici.</p>
     *
     * <p>La suppression de compte (SF-11-03) ne passe pas par là : elle efface les postes par le
     * repository, après avoir supprimé les projets. Cette garde ne la bloque donc pas.</p>
     */
    @DeleteMapping("/{hostId}")
    public ResponseEntity<Void> delete(@PathVariable UUID hostId) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        RunnerHost host = hostService.requireOwned(userId, hostId);
        int remaining = workspaceService.listByHost(userId, hostId).size();
        if (remaining > 0) {
            throw new HostHasProjectsException(host.getName(), remaining);
        }
        killSwitchService.kill(userId, hostId);
        hostService.deleteWithCredentials(userId, hostId);
        return ResponseEntity.noContent().build();
    }

    /** Génère le code d'appairage du poste — un seul suffit pour toute la machine. */
    @PostMapping("/{hostId}/pairing-code")
    public PairingCodeResponse createPairingCode(@PathVariable UUID hostId) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        PairingCode code = pairingService.createPairingCode(userId, hostId);
        return new PairingCodeResponse(code.code(), code.expiresAt());
    }

    /** Jetons runner de ce poste (métadonnées seulement, jamais la valeur). */
    @GetMapping("/{hostId}/tokens")
    public List<RunnerTokenResponse> listTokens(@PathVariable UUID hostId) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        return tokenService.list(userId, hostId).stream()
                .map(RunnerTokenResponse::from)
                .toList();
    }

    /** État « runner connecté / déconnecté » du poste. */
    @GetMapping("/{hostId}/status")
    public RunnerStatusResponse status(@PathVariable UUID hostId) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        return RunnerStatusResponse.from(statusService.hostStatus(userId, hostId));
    }

    /**
     * Révoque un jeton runner <b>et coupe sa liaison sur-le-champ</b> (F-38 / SF-38-08) : sans cela,
     * la socket ouverte sous ce jeton continuait de servir les appels. Idempotent.
     */
    @DeleteMapping("/{hostId}/tokens/{tokenId}")
    public ResponseEntity<Void> revokeToken(@PathVariable UUID hostId, @PathVariable UUID tokenId) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        killSwitchService.revokeToken(userId, hostId, tokenId);
        return ResponseEntity.noContent().build();
    }

    /**
     * <b>Coupe-circuit</b> (F-38 / SF-38-08) : révoque tous les jetons du poste, coupe la liaison en
     * cours et ramène <b>tous ses projets</b> à la cible {@code SANDBOX}. Idempotent — couper une
     * liaison déjà coupée n'est pas une erreur.
     */
    @PostMapping("/{hostId}/kill")
    public RunnerKillResponse kill(@PathVariable UUID hostId) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        hostService.requireOwned(userId, hostId);
        return RunnerKillResponse.from(killSwitchService.kill(userId, hostId));
    }
}
