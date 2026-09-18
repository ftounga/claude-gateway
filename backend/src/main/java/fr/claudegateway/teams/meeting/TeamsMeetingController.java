package fr.claudegateway.teams.meeting;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarScopeResolver;
import fr.claudegateway.teams.TeamsAccessService;
import fr.claudegateway.teams.meeting.dto.CreateMeetingRequest;
import fr.claudegateway.teams.meeting.dto.MeetingResponse;

/**
 * Les API « Réunions » de la Vigie (F-128 / SF-128-01) : rejoindre & capturer, arrêter, mettre en
 * pause/reprendre, lister, consulter — pour un poste donné.
 *
 * <p><b>Garde d'accès (comme les API du Radar).</b> Droit Teams d'abord ({@link TeamsAccessService},
 * 403), puis possession du poste (404) + activation dans la Vigie (409) via
 * {@link RadarScopeResolver#requireInVigie}. {@code hostId} est l'identifiant du poste (le
 * {@code hostRef} de l'URL front). Aucune logique métier ici : tout est dans {@link TeamsMeetingService}.</p>
 */
@RestController
@RequestMapping("/vigie/hosts/{hostId}/meetings")
public class TeamsMeetingController {

    private final TeamsMeetingService meetingService;
    private final RadarScopeResolver scopeResolver;
    private final TeamsAccessService teamsAccess;
    private final CurrentUser currentUser;

    public TeamsMeetingController(TeamsMeetingService meetingService, RadarScopeResolver scopeResolver,
            TeamsAccessService teamsAccess, CurrentUser currentUser) {
        this.meetingService = meetingService;
        this.scopeResolver = scopeResolver;
        this.teamsAccess = teamsAccess;
        this.currentUser = currentUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MeetingResponse create(@PathVariable UUID hostId, @RequestBody CreateMeetingRequest request) {
        return meetingService.create(scope(hostId), request);
    }

    @PostMapping("/{meetingId}/stop")
    public MeetingResponse stop(@PathVariable UUID hostId, @PathVariable UUID meetingId) {
        return meetingService.stop(scope(hostId), meetingId);
    }

    @PostMapping("/{meetingId}/pause")
    public MeetingResponse pause(@PathVariable UUID hostId, @PathVariable UUID meetingId) {
        return meetingService.pause(scope(hostId), meetingId);
    }

    @PostMapping("/{meetingId}/resume")
    public MeetingResponse resume(@PathVariable UUID hostId, @PathVariable UUID meetingId) {
        return meetingService.resume(scope(hostId), meetingId);
    }

    @GetMapping
    public List<MeetingResponse> list(@PathVariable UUID hostId) {
        return meetingService.list(scope(hostId));
    }

    @GetMapping("/{meetingId}")
    public MeetingResponse get(@PathVariable UUID hostId, @PathVariable UUID meetingId) {
        return meetingService.get(scope(hostId), meetingId);
    }

    private RadarScope scope(UUID hostId) {
        teamsAccess.requireAccess();
        return scopeResolver.requireInVigie(currentUser.requireId(), hostId);
    }
}
