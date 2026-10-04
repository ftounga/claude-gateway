package fr.claudegateway.governance.map.index;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.governance.GovernanceHostRef;
import fr.claudegateway.governance.GovernanceHostScope;
import fr.claudegateway.shared.error.ErrorResponse;

/**
 * <b>Le plan de la carte et la fiche d'une ressource</b> (F-173 / SF-173-01) — en lecture seule.
 *
 * <p>Même garde que la carte elle-même ({@code GET /governance/hosts/{hostRef}/map}) : accès à la
 * Forge, et un poste qui n'est pas au compte est <b>introuvable</b> (404), jamais « interdit ». Le
 * plan est lu dans l'index en base (D1) : il répond même poste hors ligne.</p>
 */
@RestController
@RequestMapping("/governance/hosts")
public class HostMapGraphController {

    private final HostMapGraph graph;
    private final HostMapIndexProperties properties;
    private final GovernanceHostScope hostScope;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;
    private final Clock clock;

    public HostMapGraphController(HostMapGraph graph, HostMapIndexProperties properties,
            GovernanceHostScope hostScope, AtelierAccessService atelierAccess, CurrentUser currentUser,
            Clock clock) {
        this.graph = graph;
        this.properties = properties;
        this.hostScope = hostScope;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @GetMapping("/{hostRef}/map/graph")
    public HostMapGraph.View graph(@PathVariable String hostRef) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        GovernanceHostRef host = hostScope.require(userId, hostRef);
        if (host.hosted() || !properties.isEnabled()) {
            return HostMapGraph.View.empty(graph.factMaxAgeDays(), 0);
        }
        return graph.graph(userId, host.hostId(), LocalDate.now(clock));
    }

    @GetMapping("/{hostRef}/map/entities/{nodeId}")
    public ResponseEntity<?> entity(@PathVariable String hostRef, @PathVariable String nodeId) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        GovernanceHostRef host = hostScope.require(userId, hostRef);
        if (host.hosted() || !properties.isEnabled()) {
            return notFound();
        }
        return graph.card(userId, host.hostId(), nodeId, LocalDate.now(clock))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(HostMapGraphController::notFound);
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", "Ressource introuvable sur cette carte."));
    }
}
