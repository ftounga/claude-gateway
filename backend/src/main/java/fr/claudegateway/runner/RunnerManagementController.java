package fr.claudegateway.runner;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.dto.RunnerAuditResponse;
import fr.claudegateway.runner.dto.RunnerStatusResponse;

/**
 * Ce qui reste attaché au <b>projet</b> côté runner (F-38, redéfini par F-48 / SF-48-01) : son état
 * et son journal.
 *
 * <p>L'appairage, les jetons et le coupe-circuit ont déménagé sur le <b>poste</b>
 * ({@code /runner-hosts/**}) : ils décrivent une machine, et les répéter par dossier était
 * exactement ce que F-48 supprime. Restent ici les deux questions qui, elles, sont bien des
 * questions de projet : « ma machine répond-elle pour ce projet ? » et « qu'a-t-on fait ici ? ».</p>
 *
 * <p>Endpoints <b>JWT</b> (chaîne principale), gardés par l'accès Atelier (Gold/ADMIN). L'identité
 * vient du {@link CurrentUser}, jamais d'un paramètre.</p>
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/runner")
public class RunnerManagementController {

    private final RunnerStatusService statusService;
    private final RunnerAuditService auditService;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public RunnerManagementController(RunnerStatusService statusService,
            RunnerAuditService auditService, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.statusService = statusService;
        this.auditService = auditService;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    /**
     * État runner de ce projet — celui du poste auquel il est rattaché. Un projet sans poste répond
     * « déconnecté » plutôt qu'une erreur : c'est l'état d'un projet neuf, que l'écran doit dire.
     */
    @GetMapping("/status")
    public RunnerStatusResponse status(@PathVariable UUID workspaceId) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        return RunnerStatusResponse.from(statusService.status(userId, workspaceId));
    }

    /**
     * Journal d'activité du runner <b>pour ce projet</b> (F-38 / SF-38-08, décision D11) : ce qui a
     * été lu, écrit et exécuté, du plus récent au plus ancien. Le journal reste par projet même
     * maintenant que le runner appartient à une machine — c'est la question à laquelle il répond.
     * {@code limit} est borné à {@code [1..200]} plutôt que refusé : un journal se consulte, il ne
     * se déverse pas.
     */
    @GetMapping("/audit")
    public List<RunnerAuditResponse> audit(@PathVariable UUID workspaceId,
            @RequestParam(required = false) Integer limit) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        return auditService.list(userId, workspaceId, limit).stream()
                .map(RunnerAuditResponse::from)
                .toList();
    }
}
