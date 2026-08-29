package fr.claudegateway.runner.channel;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registre runner <b>en mémoire</b> (défaut dev/tests) : aucune dépendance réseau, correct tant qu'un
 * seul pod sert le trafic. Sélectionné quand {@code app.runner.registry} vaut {@code in-memory} ou
 * n'est pas défini. En production multi-replica, préférer {@link PgNotifyRunnerRegistry}.
 */
@Component
@ConditionalOnProperty(prefix = "app.runner", name = "registry", havingValue = "in-memory",
        matchIfMissing = true)
public class InMemoryRunnerRegistry implements RunnerRegistry {

    private final Map<UUID, RunnerConnection> byWorkspace = new ConcurrentHashMap<>();

    @Override
    public void register(RunnerConnection connection) {
        byWorkspace.put(connection.workspaceId(), connection);
    }

    @Override
    public void unregister(UUID workspaceId, UUID tokenId) {
        // Ne retire que si la connexion courante est bien celle de ce jeton (garde anti-course).
        byWorkspace.computeIfPresent(workspaceId,
                (ws, current) -> current.tokenId().equals(tokenId) ? null : current);
    }

    @Override
    public Optional<RunnerConnection> findLocal(UUID workspaceId) {
        return Optional.ofNullable(byWorkspace.get(workspaceId));
    }

    @Override
    public boolean isConnected(UUID workspaceId) {
        return byWorkspace.containsKey(workspaceId);
    }
}
