package fr.claudegateway.teams.meeting;

import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarScopeResolver;
import fr.claudegateway.teams.TeamsAccessService;
import fr.claudegateway.teams.meeting.dto.MeetingResponse;

/**
 * <b>Purge manuelle des médias d'une réunion</b> (F-128 / SF-128-07) : effacer immédiatement l'audio et
 * les images d'une réunion, sans attendre l'échéance de rétention (la purge automatique reste faite par
 * {@link MeetingRetentionWorker}). Mêmes gardes que les autres API Réunions (droit Teams 403, possession
 * + Vigie 404/409) ; aucune logique métier ici, tout est dans {@link MeetingRetentionService}.
 */
@RestController
@RequestMapping("/vigie/hosts/{hostId}/meetings/{meetingId}")
public class TeamsMeetingRetentionController {

    private final MeetingRetentionService retention;
    private final RadarScopeResolver scopeResolver;
    private final TeamsAccessService teamsAccess;
    private final CurrentUser currentUser;

    public TeamsMeetingRetentionController(MeetingRetentionService retention,
            RadarScopeResolver scopeResolver, TeamsAccessService teamsAccess, CurrentUser currentUser) {
        this.retention = retention;
        this.scopeResolver = scopeResolver;
        this.teamsAccess = teamsAccess;
        this.currentUser = currentUser;
    }

    /** Purge les médias lourds (audio + images) de la réunion ; rend l'artefact à jour. */
    @DeleteMapping("/media")
    public MeetingResponse purgeMedia(@PathVariable UUID hostId, @PathVariable UUID meetingId) {
        return retention.purgeMedia(scope(hostId), meetingId);
    }

    private RadarScope scope(UUID hostId) {
        teamsAccess.requireAccess();
        return scopeResolver.requireInVigie(currentUser.requireId(), hostId);
    }
}
