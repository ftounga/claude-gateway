package fr.claudegateway.radar;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.radar.dto.RadarNewsViews.NewsRequest;
import fr.claudegateway.radar.dto.RadarNewsViews.NewsUndoView;
import fr.claudegateway.radar.dto.RadarNewsViews.NewsView;
import fr.claudegateway.teams.TeamsAccessService;

/**
 * <b>Donner la nouvelle</b> (F-104 / SF-104-02) : nourrir le Radar d'un client de la Vigie, et annuler une
 * nouvelle.
 *
 * <p><b>Droit et isolation</b> : ceux de {@code RadarController} — droit Vigie d'abord (sans lui, on ne dit
 * rien des postes), puis poste possédé (404) et activé dans la Vigie (409).</p>
 */
@RestController
@RequestMapping("/radar/hosts/{hostId}/news")
public class RadarNewsController {

    private final RadarNewsService newsService;
    private final RadarScopeResolver scopeResolver;
    private final TeamsAccessService teamsAccess;
    private final CurrentUser currentUser;

    public RadarNewsController(RadarNewsService newsService, RadarScopeResolver scopeResolver,
            TeamsAccessService teamsAccess, CurrentUser currentUser) {
        this.newsService = newsService;
        this.scopeResolver = scopeResolver;
        this.teamsAccess = teamsAccess;
        this.currentUser = currentUser;
    }

    /** Une nouvelle : un tour d'agent muni des outils Radar, décompté. */
    @PostMapping
    public NewsView give(@PathVariable UUID hostId, @RequestBody(required = false) NewsRequest request) {
        return newsService.give(scope(hostId), request == null ? null : request.text());
    }

    /** Annule une nouvelle entière : ses corrections, puis sa preuve. */
    @PostMapping("/{evidenceId}/undo")
    public NewsUndoView undo(@PathVariable UUID hostId, @PathVariable UUID evidenceId) {
        return newsService.undo(scope(hostId), evidenceId);
    }

    private RadarScope scope(UUID hostId) {
        teamsAccess.requireAccess();
        return scopeResolver.requireInVigie(currentUser.requireId(), hostId);
    }
}
