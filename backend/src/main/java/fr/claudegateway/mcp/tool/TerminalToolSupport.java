package fr.claudegateway.mcp.tool;

import java.util.UUID;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.mcp.McpCallContext;
import fr.claudegateway.mcp.McpHostAccess;
import fr.claudegateway.mcp.McpScopes;
import fr.claudegateway.mcp.McpTerminalAccess;
import fr.claudegateway.mcp.McpToolSupport;

/**
 * Gardes communes aux outils Terminaux (F-112 / SF-112-05) : périmètre {@code terminaux:ecrire},
 * projet possédé (isolation {@code user_id}), droit d'accès au terminal (Forge/Vigie, bypass ADMIN)
 * et accès poste par poste. Rassemblées ici pour que chaque outil reste un relais mince.
 */
final class TerminalToolSupport {

    private TerminalToolSupport() {
    }

    /** Le projet résolu et vérifié, ou l'erreur à rendre (l'un des deux est nul). */
    record Guarded(Workspace workspace, CallToolResult error) {
    }

    static Guarded resolve(McpCallContext ctx, UUID projectId, WorkspaceService workspaceService,
            McpTerminalAccess terminalAccess, McpHostAccess hostAccess, McpToolSupport support) {
        if (!ctx.hasScope(McpScopes.TERMINAUX_ECRIRE)) {
            return new Guarded(null, support.deniedScope(McpScopes.TERMINAUX_ECRIRE));
        }
        if (projectId == null) {
            return new Guarded(null, support.error("project_id manquant ou invalide (UUID attendu)."));
        }
        Workspace workspace;
        try {
            workspace = workspaceService.requireOwned(ctx.user().id(), projectId);
        } catch (RuntimeException ex) {
            return new Guarded(null, support.error("Projet introuvable."));
        }
        if (!terminalAccess.hasTerminalAccess(ctx.user(), projectId)) {
            return new Guarded(null, support.error(
                    "Accès terminal refusé : ce compte n'a pas le droit Forge (ou Vigie) requis."));
        }
        if (workspace.getHostId() != null && !hostAccess.canAccess(ctx, workspace.getHostId())) {
            return new Guarded(null, support.deniedHost());
        }
        return new Guarded(workspace, null);
    }
}
