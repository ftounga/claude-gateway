package fr.claudegateway.mcp.tool;

import java.util.UUID;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import fr.claudegateway.mcp.McpCallContext;
import fr.claudegateway.mcp.McpHostAccess;
import fr.claudegateway.mcp.McpToolSupport;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarScopeResolver;

/**
 * Gardes communes aux outils Vigie et Radar (F-112 / SF-112-06) : périmètre, {@code host_id} valide,
 * accès poste par poste, et résolution du {@link RadarScope} via
 * {@link RadarScopeResolver#requireInVigie} (possession du poste + activation Vigie). Chaque outil
 * reste ainsi un relais mince.
 */
final class RadarToolSupport {

    private RadarToolSupport() {
    }

    /** Le périmètre Radar résolu et vérifié, ou l'erreur à rendre (l'un des deux est nul). */
    record Guarded(RadarScope scope, CallToolResult error) {
    }

    static Guarded resolve(McpCallContext ctx, String requiredScope, UUID hostId,
            RadarScopeResolver scopeResolver, McpHostAccess hostAccess, McpToolSupport support) {
        if (!ctx.hasScope(requiredScope)) {
            return new Guarded(null, support.deniedScope(requiredScope));
        }
        if (hostId == null) {
            return new Guarded(null, support.error("host_id manquant ou invalide (UUID attendu)."));
        }
        if (!hostAccess.canAccess(ctx, hostId)) {
            return new Guarded(null, support.deniedHost());
        }
        RadarScope scope;
        try {
            scope = scopeResolver.requireInVigie(ctx.user().id(), hostId);
        } catch (RuntimeException ex) {
            return new Guarded(null, support.error(
                    "Poste introuvable ou non activé dans la Vigie."));
        }
        return new Guarded(scope, null);
    }
}
