package fr.claudegateway.radar.sync;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarScopeResolver;
import fr.claudegateway.teams.TeamsAccessService;

/**
 * <b>La synchro du soir</b> d'un poste (F-100) : vérification guidée (SF-100-01).
 *
 * <p><b>Droit</b> : celui du Radar, provisoirement le droit Teams (comme {@code RadarController}).
 * <b>Isolation</b> : le poste est vérifié comme possédé avant tout appel au runner ; un poste d'autrui
 * rend « introuvable » et le runner n'est jamais appelé.</p>
 */
@RestController
@RequestMapping("/radar/hosts/{hostId}")
public class RadarSyncController {

    private final RadarVerificationService verificationService;
    private final RadarScopeResolver scopeResolver;
    private final TeamsAccessService teamsAccess;
    private final CurrentUser currentUser;

    public RadarSyncController(RadarVerificationService verificationService, RadarScopeResolver scopeResolver,
            TeamsAccessService teamsAccess, CurrentUser currentUser) {
        this.verificationService = verificationService;
        this.scopeResolver = scopeResolver;
        this.teamsAccess = teamsAccess;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------ vérification guidée (SF-100-01)

    @PostMapping("/verification")
    public VerificationResponse verify(@PathVariable UUID hostId) {
        return VerificationResponse.of(verificationService.verify(scope(hostId)));
    }

    @GetMapping("/verification")
    public VerificationResponse verification(@PathVariable UUID hostId) {
        return VerificationResponse.of(verificationService.current(scope(hostId)));
    }

    @DeleteMapping("/verification")
    public VerificationResponse resetVerification(@PathVariable UUID hostId) {
        return VerificationResponse.of(verificationService.reset(scope(hostId)));
    }

    private RadarScope scope(UUID hostId) {
        teamsAccess.requireAccess();
        return scopeResolver.require(currentUser.requireId(), hostId);
    }

    /** La vérification telle que l'écran la rend : quatre cases et « tout est vu ». */
    public record VerificationResponse(boolean complete, OffsetDateTime verifiedAt, RadarVerification.Check session,
            RadarVerification.Check conversations, RadarVerification.Check meetings,
            RadarVerification.Check transcripts) {

        static VerificationResponse of(RadarVerification v) {
            return new VerificationResponse(v.complete(), v.verifiedAt(), v.session(), v.conversations(),
                    v.meetings(), v.transcripts());
        }
    }
}
