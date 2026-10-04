package fr.claudegateway.governance.map.index;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.governance.GovernanceHostRef;
import fr.claudegateway.governance.GovernanceHostScope;

/**
 * <b>Ce que la carte gagnerait à consolider</b> (F-174 / SF-174-06, D9) — en lecture seule.
 *
 * <p>Même garde que la carte elle-même ({@code GET /governance/hosts/{hostRef}/map}) : accès à la
 * Forge, et un poste qui n'est pas au compte est <b>introuvable</b> (404), jamais « interdit ». Rien
 * n'est écrit : chaque proposition porte la demande à confier à la Forge.</p>
 */
@RestController
@RequestMapping("/governance/hosts")
public class HostMapConsolidationController {

    private final HostMapConsolidation consolidation;
    private final HostMapIndexProperties properties;
    private final GovernanceHostScope hostScope;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;
    private final Clock clock;

    public HostMapConsolidationController(HostMapConsolidation consolidation,
            HostMapIndexProperties properties, GovernanceHostScope hostScope,
            AtelierAccessService atelierAccess, CurrentUser currentUser, Clock clock) {
        this.consolidation = consolidation;
        this.properties = properties;
        this.hostScope = hostScope;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @GetMapping("/{hostRef}/map/consolidation")
    public HostMapConsolidation.View consolidation(@PathVariable String hostRef) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        GovernanceHostRef host = hostScope.require(userId, hostRef);
        if (host.hosted() || !properties.isEnabled()) {
            return new HostMapConsolidation.View(false, 0, List.of());
        }
        return consolidation.proposals(userId, host.hostId(), LocalDate.now(clock));
    }
}
