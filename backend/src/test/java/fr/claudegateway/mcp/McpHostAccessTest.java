package fr.claudegateway.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.auth.AuthenticatedUser;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostNotFoundException;
import fr.claudegateway.runner.host.RunnerHostService;
import fr.claudegateway.user.UserRole;

/**
 * Tests unitaires de l'accès poste par poste MCP (F-112 / SF-112-04, garde §4).
 */
class McpHostAccessTest {

    private final RunnerHostService hostService = mock(RunnerHostService.class);
    private final McpHostAccess access = new McpHostAccess(hostService);

    private final UUID userId = UUID.randomUUID();
    private final AuthenticatedUser user = new AuthenticatedUser(userId, "u@example.com", UserRole.USER);

    private McpCallContext personal(Set<UUID> hostIds) {
        return new McpCallContext(user, McpAuthKind.PERSONAL, UUID.randomUUID(), "CI", Set.of(), hostIds);
    }

    private McpCallContext oauth() {
        return new McpCallContext(user, McpAuthKind.OAUTH, null, "Claude", Set.of(), Set.of());
    }

    private void ownsHost(UUID hostId) {
        when(hostService.requireOwned(eq(userId), eq(hostId))).thenReturn(mock(RunnerHost.class));
    }

    private void doesNotOwnHost(UUID hostId) {
        when(hostService.requireOwned(eq(userId), eq(hostId)))
                .thenThrow(new RunnerHostNotFoundException("nope"));
    }

    @Test
    void personalTokenGrantsOnlyListedAndOwnedHosts() {
        UUID listed = UUID.randomUUID();
        ownsHost(listed);
        assertThat(access.canAccess(personal(Set.of(listed)), listed)).isTrue();
    }

    @Test
    void personalTokenDeniesHostNotInList() {
        UUID other = UUID.randomUUID();
        assertThat(access.canAccess(personal(Set.of(UUID.randomUUID())), other)).isFalse();
    }

    @Test
    void personalTokenDeniesListedButNotOwnedHost() {
        UUID listed = UUID.randomUUID();
        doesNotOwnHost(listed);
        assertThat(access.canAccess(personal(Set.of(listed)), listed)).isFalse();
    }

    @Test
    void oauthGrantsOwnedHost() {
        UUID owned = UUID.randomUUID();
        ownsHost(owned);
        assertThat(access.canAccess(oauth(), owned)).isTrue();
    }

    @Test
    void oauthDeniesUnownedHost() {
        UUID foreign = UUID.randomUUID();
        doesNotOwnHost(foreign);
        assertThat(access.canAccess(oauth(), foreign)).isFalse();
    }

    @Test
    void deniesNullHost() {
        assertThat(access.canAccess(oauth(), null)).isFalse();
    }
}
