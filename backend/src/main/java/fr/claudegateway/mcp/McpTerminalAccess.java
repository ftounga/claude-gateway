package fr.claudegateway.mcp;

import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.AuthenticatedUser;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.SpaceEntitlementService;
import fr.claudegateway.user.UserRole;

/**
 * Rejoue, pour le serveur MCP, le contrôle d'accès aux terminaux de l'Atelier (F-107 / SF-107-07,
 * miroir de {@code AtelierAccessService}) — mais résolu depuis le {@link McpCallContext}, car le
 * {@code SecurityContext} n'est pas disponible sur le thread d'exécution d'un outil MCP (il est
 * capturé sur le thread servlet, cf. {@code McpTransportContextFactory}).
 *
 * <p>Mêmes droits (garde §6.2) : bypass {@code ADMIN} ; sinon droit <b>Forge</b> pour tout terminal,
 * ou droit <b>runner</b> (Forge ou Vigie) pour son propre <b>terminal Teams</b>. L'isolation
 * {@code user_id} est en aval (services d'atelier) ; ici on ne fait qu'appliquer le droit d'espace.</p>
 */
@Component
public class McpTerminalAccess {

    private final SpaceEntitlementService entitlementService;
    private final WorkspaceRepository workspaceRepository;

    public McpTerminalAccess(SpaceEntitlementService entitlementService,
            WorkspaceRepository workspaceRepository) {
        this.entitlementService = entitlementService;
        this.workspaceRepository = workspaceRepository;
    }

    /** Le droit d'utiliser l'Atelier (Forge), pour les listes et le lancement de tours. */
    public boolean hasForgeAccess(AuthenticatedUser user) {
        return isAllowed(user);
    }

    /** Le droit d'utiliser ce terminal désigné (projet, terminal de poste, ou terminal Teams). */
    public boolean hasTerminalAccess(AuthenticatedUser user, UUID workspaceId) {
        if (isAllowed(user)) {
            return true;
        }
        return isOwnTeamsTerminal(user, workspaceId) && isRunnerAllowed(user);
    }

    private boolean isOwnTeamsTerminal(AuthenticatedUser user, UUID workspaceId) {
        return workspaceId != null && workspaceRepository.findByIdAndUserId(workspaceId, user.id())
                .map(Workspace::isTeamsTerminal)
                .orElse(false);
    }

    private boolean isRunnerAllowed(AuthenticatedUser user) {
        return isAllowed(user) || entitlementService.isEntitled(user.id(), EntitlementSpace.VIGIE);
    }

    private boolean isAllowed(AuthenticatedUser user) {
        if (user.role() == UserRole.ADMIN) {
            return true;
        }
        return entitlementService.isEntitled(user.id(), EntitlementSpace.FORGE);
    }
}
