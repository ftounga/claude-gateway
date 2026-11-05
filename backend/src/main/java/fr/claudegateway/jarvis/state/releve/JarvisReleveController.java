package fr.claudegateway.jarvis.state.releve;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.veille.JarvisWatchDtos;
import fr.claudegateway.atelier.veille.JarvisWatchService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * <b>Le relevé, côté écran</b> (F-247 / SF-247-08) : les sondes de relevé d'un client, la mise en place (tour
 * d'inventaire unique) et « Tout valider ». Tout sous le {@code user_id} du principal ; un client d'un autre compte ⇒
 * 404.
 */
@RestController
public class JarvisReleveController {

    private final JarvisReleveSetupService setup;
    private final JarvisWatchService watches;
    private final JarvisStateRegistry registry;
    private final RunnerHostRepository hosts;
    private final CurrentUser currentUser;

    public JarvisReleveController(JarvisReleveSetupService setup, JarvisWatchService watches,
            JarvisStateRegistry registry, RunnerHostRepository hosts, CurrentUser currentUser) {
        this.setup = setup;
        this.watches = watches;
        this.registry = registry;
        this.hosts = hosts;
        this.currentUser = currentUser;
    }

    /** Une sonde de relevé, telle que l'écran la montre. */
    public record ProbeView(UUID id, UUID workspaceId, String workspaceName, String name, String platform,
            String scope, String command, boolean proposed, boolean active, OffsetDateTime testedAt,
            String testSummary, OffsetDateTime lastRunAt, String lastRunStatus, boolean broken) {
    }

    /** Le relevé d'un client : sa mise en place et ses sondes. */
    public record ReleveView(OffsetDateTime setupAt, List<ProbeView> probes) {
    }

    @GetMapping("/jarvis/state/clients/{hostId}/releve")
    public ResponseEntity<ReleveView> view(@PathVariable UUID hostId) {
        UUID userId = currentUser.requireId();
        if (hosts.findByIdAndUserId(hostId, userId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        List<ProbeView> probes = watches.listForAccount(userId, hostId).stream()
                .filter(s -> "STATE".equals(s.watch().purpose()))
                .map(s -> probe(s.watch(), s.workspaceName())).toList();
        return ResponseEntity.ok(new ReleveView(registry.settingsOf(userId, hostId).getReleveSetupAt(), probes));
    }

    @PostMapping("/jarvis/state/clients/{hostId}/releve/setup")
    public ResponseEntity<?> start(@PathVariable UUID hostId) {
        UUID userId = currentUser.requireId();
        if (hosts.findByIdAndUserId(hostId, userId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        JarvisReleveSetupService.Setup s = setup.start(userId, hostId);
        if (!s.started()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", s.code(), "message", s.message()));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("workspaceId", s.workspaceId().toString(),
                "turnId", s.turnId().toString()));
    }

    @PostMapping("/jarvis/state/clients/{hostId}/releve/validate-all")
    public ResponseEntity<List<JarvisWatchDtos.ValidateResult>> validateAll(@PathVariable UUID hostId) {
        UUID userId = currentUser.requireId();
        if (hosts.findByIdAndUserId(hostId, userId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(watches.validateAllState(userId, hostId));
    }

    private static ProbeView probe(JarvisWatchDtos.JarvisWatchView w, String workspaceName) {
        return new ProbeView(w.id(), w.workspaceId(), workspaceName, w.name(), w.platform(), w.scope(), w.command(),
                w.proposed(), w.active(), w.testedAt(), w.testSummary(), w.lastRunAt(), w.lastRunStatus(), w.broken());
    }
}
