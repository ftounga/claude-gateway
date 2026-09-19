package fr.claudegateway.teams.meeting;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarScopeResolver;
import fr.claudegateway.teams.TeamsAccessService;
import fr.claudegateway.teams.meeting.dto.MeetingCardPromotion;

/**
 * <b>Enrichir la carte du poste depuis une réunion</b> (F-128 / SF-128-11) : ranger les faits durables
 * d'une réunion dans la carte du poste. Mêmes gardes que les autres API Réunions (droit Teams 403,
 * possession + Vigie 404/409) ; aucune logique métier ici, tout est dans
 * {@link MeetingCardPromotionService} (extraction via {@code AIProvider}, écriture via le chemin carte
 * existant, quota, isolation).
 */
@RestController
@RequestMapping("/vigie/hosts/{hostId}/meetings/{meetingId}")
public class TeamsMeetingCardController {

    private final MeetingCardPromotionService promotion;
    private final RadarScopeResolver scopeResolver;
    private final TeamsAccessService teamsAccess;
    private final CurrentUser currentUser;

    public TeamsMeetingCardController(MeetingCardPromotionService promotion,
            RadarScopeResolver scopeResolver, TeamsAccessService teamsAccess, CurrentUser currentUser) {
        this.promotion = promotion;
        this.scopeResolver = scopeResolver;
        this.teamsAccess = teamsAccess;
        this.currentUser = currentUser;
    }

    /** Extrait les faits durables de la réunion et les range dans la carte du poste. */
    @PostMapping("/promote-to-card")
    public MeetingCardPromotion promoteToCard(@PathVariable UUID hostId, @PathVariable UUID meetingId) {
        return promotion.promote(scope(hostId), meetingId);
    }

    private RadarScope scope(UUID hostId) {
        teamsAccess.requireAccess();
        return scopeResolver.requireInVigie(currentUser.requireId(), hostId);
    }
}
