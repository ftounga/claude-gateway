package fr.claudegateway.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.AuthenticatedUser;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.SpaceEntitlementService;
import fr.claudegateway.user.UserRole;

/**
 * Tests unitaires de l'accès aux terminaux côté MCP (F-112 / SF-112-05) : mêmes règles que
 * {@code AtelierAccessService}, résolues depuis l'identité du jeton.
 */
class McpTerminalAccessTest {

    private final SpaceEntitlementService entitlements = mock(SpaceEntitlementService.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final McpTerminalAccess access = new McpTerminalAccess(entitlements, workspaces);

    private AuthenticatedUser user(UserRole role) {
        return new AuthenticatedUser(UUID.randomUUID(), "u@example.com", role);
    }

    @Test
    void adminBypassesEntitlement() {
        AuthenticatedUser admin = user(UserRole.ADMIN);
        assertThat(access.hasForgeAccess(admin)).isTrue();
        assertThat(access.hasTerminalAccess(admin, UUID.randomUUID())).isTrue();
    }

    @Test
    void forgeEntitledUserHasAccess() {
        AuthenticatedUser u = user(UserRole.USER);
        when(entitlements.isEntitled(eq(u.id()), eq(EntitlementSpace.FORGE))).thenReturn(true);
        assertThat(access.hasForgeAccess(u)).isTrue();
        assertThat(access.hasTerminalAccess(u, UUID.randomUUID())).isTrue();
    }

    @Test
    void userWithoutEntitlementIsDenied() {
        AuthenticatedUser u = user(UserRole.USER);
        when(entitlements.isEntitled(any(UUID.class), any(EntitlementSpace.class))).thenReturn(false);
        when(workspaces.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());
        assertThat(access.hasForgeAccess(u)).isFalse();
        assertThat(access.hasTerminalAccess(u, UUID.randomUUID())).isFalse();
    }

    @Test
    void vigieUserReachesOwnTeamsTerminal() {
        AuthenticatedUser u = user(UserRole.USER);
        UUID teamsTerminal = UUID.randomUUID();
        Workspace ws = mock(Workspace.class);
        when(ws.isTeamsTerminal()).thenReturn(true);
        when(workspaces.findByIdAndUserId(eq(teamsTerminal), eq(u.id()))).thenReturn(Optional.of(ws));
        when(entitlements.isEntitled(eq(u.id()), eq(EntitlementSpace.FORGE))).thenReturn(false);
        when(entitlements.isEntitled(eq(u.id()), eq(EntitlementSpace.VIGIE))).thenReturn(true);
        assertThat(access.hasTerminalAccess(u, teamsTerminal)).isTrue();
        // Mais pas un projet ordinaire, faute de Forge.
        when(workspaces.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());
        assertThat(access.hasTerminalAccess(u, UUID.randomUUID())).isFalse();
    }
}
