package fr.claudegateway.radar;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.radar.RadarDraftService.DraftView;
import fr.claudegateway.teams.TeamsAccessService;

/**
 * <b>Relances et présentations préparées</b> (F-104 / SF-104-05) : un brouillon sur un engagement, jamais envoyé.
 *
 * <p><b>Droit et isolation</b> : droit Vigie d'abord, puis poste possédé (404) et activé dans la Vigie (409),
 * puis engagement du périmètre (404).</p>
 */
@RestController
@RequestMapping("/radar/hosts/{hostId}/commitments/{commitmentId}")
public class RadarDraftController {

    private final RadarDraftService draftService;
    private final RadarScopeResolver scopeResolver;
    private final TeamsAccessService teamsAccess;
    private final CurrentUser currentUser;

    public RadarDraftController(RadarDraftService draftService, RadarScopeResolver scopeResolver,
            TeamsAccessService teamsAccess, CurrentUser currentUser) {
        this.draftService = draftService;
        this.scopeResolver = scopeResolver;
        this.teamsAccess = teamsAccess;
        this.currentUser = currentUser;
    }

    /** Un appel au fournisseur, décompté ; rien n'est persisté ni envoyé. */
    @PostMapping("/draft")
    public DraftView draft(@PathVariable UUID hostId, @PathVariable UUID commitmentId) {
        teamsAccess.requireAccess();
        return draftService.prepare(scopeResolver.requireInVigie(currentUser.requireId(), hostId), commitmentId);
    }
}
