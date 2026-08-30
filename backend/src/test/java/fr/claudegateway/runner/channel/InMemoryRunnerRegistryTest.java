package fr.claudegateway.runner.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Tests du registre runner en mémoire (F-38 / SF-38-02) : présence, résolution locale, retrait avec
 * garde anti-course, et isolation par workspace.
 */
class InMemoryRunnerRegistryTest {

    private final InMemoryRunnerRegistry registry = new InMemoryRunnerRegistry();

    private RunnerConnection connection(UUID workspaceId, UUID tokenId) {
        return new RunnerConnection(workspaceId, UUID.randomUUID(), tokenId, "node-1",
                OffsetDateTime.now());
    }

    @Test
    void registerMakesWorkspaceConnected() {
        UUID ws = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        assertThat(registry.isConnected(ws)).isFalse();

        registry.register(connection(ws, token));

        assertThat(registry.isConnected(ws)).isTrue();
        assertThat(registry.findLocal(ws)).map(RunnerConnection::tokenId).contains(token);
    }

    @Test
    void unregisterRemovesConnectionOfSameToken() {
        UUID ws = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        registry.register(connection(ws, token));

        registry.unregister(ws, token);

        assertThat(registry.isConnected(ws)).isFalse();
        assertThat(registry.findLocal(ws)).isEmpty();
    }

    @Test
    void unregisterOfStaleTokenDoesNotEvictNewerConnection() {
        UUID ws = UUID.randomUUID();
        UUID oldToken = UUID.randomUUID();
        UUID newToken = UUID.randomUUID();
        registry.register(connection(ws, oldToken));
        registry.register(connection(ws, newToken)); // reconnexion sous un nouveau jeton

        // La fermeture tardive de l'ancienne session ne doit pas effacer la nouvelle.
        registry.unregister(ws, oldToken);

        assertThat(registry.isConnected(ws)).isTrue();
        assertThat(registry.findLocal(ws)).map(RunnerConnection::tokenId).contains(newToken);
    }

    @Test
    void connectionsAreIsolatedByWorkspace() {
        UUID wsA = UUID.randomUUID();
        UUID wsB = UUID.randomUUID();
        registry.register(connection(wsA, UUID.randomUUID()));

        assertThat(registry.isConnected(wsA)).isTrue();
        assertThat(registry.isConnected(wsB)).isFalse();
        assertThat(registry.findLocal(wsB)).isEmpty();
    }

    @Test
    void findRemoteIsAlwaysEmptyBecauseThereIsOnlyOneNode() {
        UUID ws = UUID.randomUUID();
        registry.register(connection(ws, UUID.randomUUID()));

        // Un registre en mémoire ne connaît qu'un pod : aucun relais inter-pods n'est possible ni
        // nécessaire (F-38 / SF-38-12).
        assertThat(registry.findRemote(ws)).isEmpty();
        assertThat(registry.findRemote(UUID.randomUUID())).isEmpty();
    }
}
