package fr.claudegateway.radar;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.radar.dto.RadarSubjectPageViews.UnknownView;
import fr.claudegateway.teams.TeamsAccessService;

/**
 * <b>La page d'un sujet</b> de la Vigie (F-103), au-delà de la vue du sujet lue par
 * {@code RadarController} : ce que le Radar ne sait pas (SF-103-02).
 *
 * <p><b>Droit et isolation</b> : ceux de {@code RadarController} — droit d'abord (sans lui, on ne dit rien
 * des postes), puis poste possédé (404) et activé dans la Vigie (409), puis sujet du périmètre (404).</p>
 */
@RestController
@RequestMapping("/radar/hosts/{hostId}/subjects/{subjectId}")
public class RadarSubjectPageController {

    private final RadarUnknownsService unknownsService;
    private final RadarScopeResolver scopeResolver;
    private final TeamsAccessService teamsAccess;
    private final CurrentUser currentUser;

    public RadarSubjectPageController(RadarUnknownsService unknownsService, RadarScopeResolver scopeResolver,
            TeamsAccessService teamsAccess, CurrentUser currentUser) {
        this.unknownsService = unknownsService;
        this.scopeResolver = scopeResolver;
        this.teamsAccess = teamsAccess;
        this.currentUser = currentUser;
    }

    /** Ce que le Radar ne sait pas sur le sujet, et à qui le demander. */
    @GetMapping("/unknowns")
    public List<UnknownView> unknowns(@PathVariable UUID hostId, @PathVariable UUID subjectId) {
        return unknownsService.unknowns(scope(hostId), subjectId);
    }

    private RadarScope scope(UUID hostId) {
        teamsAccess.requireAccess();
        return scopeResolver.requireInVigie(currentUser.requireId(), hostId);
    }
}
