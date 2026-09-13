package fr.claudegateway.radar;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.radar.dto.RadarBoardViews.BriefView;
import fr.claudegateway.teams.TeamsAccessService;

/**
 * <b>L'onglet Radar</b> d'un client de la Vigie (F-102) : le résumé du matin (SF-102-01).
 *
 * <p><b>Droit et isolation</b> : ceux de {@code RadarController} — droit d'abord (sans lui, on ne dit rien
 * des postes), puis poste possédé (404) et activé dans la Vigie (409).</p>
 */
@RestController
@RequestMapping("/radar/hosts/{hostId}")
public class RadarBoardController {

    private final RadarBriefService briefService;
    private final RadarScopeResolver scopeResolver;
    private final TeamsAccessService teamsAccess;
    private final CurrentUser currentUser;

    public RadarBoardController(RadarBriefService briefService, RadarScopeResolver scopeResolver,
            TeamsAccessService teamsAccess, CurrentUser currentUser) {
        this.briefService = briefService;
        this.scopeResolver = scopeResolver;
        this.teamsAccess = teamsAccess;
        this.currentUser = currentUser;
    }

    /** Le résumé du matin : ce qui a bougé, les compteurs, la couverture. */
    @GetMapping("/brief")
    public BriefView brief(@PathVariable UUID hostId) {
        return briefService.brief(scope(hostId));
    }

    private RadarScope scope(UUID hostId) {
        teamsAccess.requireAccess();
        return scopeResolver.requireInVigie(currentUser.requireId(), hostId);
    }
}
