package fr.claudegateway.teams.meeting;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarScopeResolver;
import fr.claudegateway.teams.TeamsAccessService;
import fr.claudegateway.teams.meeting.dto.MeetingActionsToRadar;
import fr.claudegateway.teams.meeting.dto.PushActionsToRadarRequest;

/**
 * <b>Pousser les actions d'une réunion dans le Radar</b> (F-128 / SF-128-06) : les actions retenues
 * deviennent des engagements « À faire par moi » sur un sujet. Mêmes gardes que les autres API Réunions
 * (droit Teams 403, possession + Vigie 404/409) ; aucune logique métier ici, tout est dans
 * {@link MeetingActionsToRadarService} (réutilise le cœur d'engagement du Radar, isolation).
 */
@RestController
@RequestMapping("/vigie/hosts/{hostId}/meetings/{meetingId}")
public class TeamsMeetingRadarController {

    private final MeetingActionsToRadarService actionsToRadar;
    private final RadarScopeResolver scopeResolver;
    private final TeamsAccessService teamsAccess;
    private final CurrentUser currentUser;

    public TeamsMeetingRadarController(MeetingActionsToRadarService actionsToRadar,
            RadarScopeResolver scopeResolver, TeamsAccessService teamsAccess, CurrentUser currentUser) {
        this.actionsToRadar = actionsToRadar;
        this.scopeResolver = scopeResolver;
        this.teamsAccess = teamsAccess;
        this.currentUser = currentUser;
    }

    /** Pousse les actions retenues de la réunion en engagements « À faire par moi » sur un sujet. */
    @PostMapping("/actions-to-radar")
    public MeetingActionsToRadar pushActions(@PathVariable UUID hostId, @PathVariable UUID meetingId,
            @RequestBody(required = false) PushActionsToRadarRequest request) {
        UUID subjectId = request == null ? null : request.subjectId();
        List<String> actions = request == null ? List.of() : request.actions();
        return actionsToRadar.push(scope(hostId), meetingId, subjectId, actions);
    }

    private RadarScope scope(UUID hostId) {
        teamsAccess.requireAccess();
        return scopeResolver.requireInVigie(currentUser.requireId(), hostId);
    }
}
