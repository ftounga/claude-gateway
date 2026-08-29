package fr.claudegateway.runner;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.channel.RunnerRegistry;

/**
 * Calcule l'état « runner connecté » d'un workspace (F-38 / SF-38-02). Le workspace est d'abord
 * vérifié comme appartenant à l'utilisateur ({@link WorkspaceService#requireOwned}, isolation
 * {@code user_id}) — jamais depuis un paramètre client.
 *
 * <p>{@code connected} combine deux signaux : la présence dans le {@link RunnerRegistry} (immédiate,
 * locale ou cross-replica via PgNotify) <b>et</b> la fraîcheur de {@code last_seen_at} (base
 * partagée). Ce second critère rend le statut correct même si la socket vit sur l'autre pod ou si un
 * pod vient de démarrer sans avoir encore reçu les événements de présence existants.</p>
 */
@Service
public class RunnerStatusService {

    private final RunnerTokenRepository tokenRepository;
    private final RunnerRegistry registry;
    private final WorkspaceService workspaceService;
    private final Duration staleAfter;

    public RunnerStatusService(
            RunnerTokenRepository tokenRepository,
            RunnerRegistry registry,
            WorkspaceService workspaceService,
            @Value("${app.runner.heartbeat.stale-after:PT90S}") Duration staleAfter) {
        this.tokenRepository = tokenRepository;
        this.registry = registry;
        this.workspaceService = workspaceService;
        this.staleAfter = staleAfter;
    }

    /** État runner du workspace, pour son propriétaire. */
    @Transactional(readOnly = true)
    public RunnerStatus status(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId);
        Optional<OffsetDateTime> lastSeen = tokenRepository
                .findByUserIdAndWorkspaceIdOrderByCreatedAtDesc(userId, workspaceId).stream()
                .map(RunnerToken::getLastSeenAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder());
        boolean heartbeatFresh = lastSeen
                .map(seen -> seen.isAfter(OffsetDateTime.now().minus(staleAfter)))
                .orElse(false);
        boolean connected = registry.isConnected(workspaceId) || heartbeatFresh;
        return new RunnerStatus(connected, lastSeen.orElse(null));
    }

    /** État runner : connecté ou non, dernière activité observée (peut être {@code null}). */
    public record RunnerStatus(boolean connected, OffsetDateTime lastSeenAt) {
    }
}
