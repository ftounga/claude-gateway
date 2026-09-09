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

    private final Map<UUID, RunnerConnection> byHost = new ConcurrentHashMap<>();

    @Override
    public void register(RunnerConnection connection) {
        byHost.put(connection.hostId(), connection);
    }

    @Override
    public void unregister(UUID hostId, UUID tokenId) {
        // Ne retire que si la connexion courante est bien celle de ce jeton (garde anti-course).
        byHost.computeIfPresent(hostId,
                (host, current) -> current.tokenId().equals(tokenId) ? null : current);
    }

    @Override
    public Optional<RunnerConnection> findLocal(UUID hostId) {
        return Optional.ofNullable(byHost.get(hostId));
    }

    /**
     * Toujours vide : un registre en mémoire ne connaît qu'un seul pod, celui qui l'héberge. Aucun
     * relais inter-pods n'est donc possible ni nécessaire (F-38 / SF-38-12).
     */
    @Override
    public Optional<RemoteRunnerNode> findRemote(UUID hostId) {
        return Optional.empty();
    }

    @Override
    public boolean isConnected(UUID hostId) {
        return byHost.containsKey(hostId);
    }
}
