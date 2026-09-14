package fr.claudegateway.mcp;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import fr.claudegateway.user.UserRole;

/**
 * Double garde des outils d'administration MCP (cadrage F-112 §4/§8) : le périmètre {@code admin}
 * <b>et</b> le rôle {@code ADMIN}. Un périmètre n'est jamais plus large que les droits — un compte
 * non-ADMIN qui se serait vu accorder {@code admin} reste refusé. Miroir d'{@code AdminService.assertAdmin},
 * mais résolu depuis {@link McpCallContext} (le {@code SecurityContext} n'est pas sur le thread d'outil).
 */
@Component
public class McpAdminGuard {

    private final McpToolSupport support;

    public McpAdminGuard(McpToolSupport support) {
        this.support = support;
    }

    /**
     * {@code null} si l'appelant peut utiliser un outil d'administration ; sinon le
     * {@link CallToolResult} de refus à rendre.
     */
    public CallToolResult check(McpCallContext ctx) {
        if (!ctx.hasScope(McpScopes.ADMIN)) {
            return support.deniedScope(McpScopes.ADMIN);
        }
        if (ctx.user().role() != UserRole.ADMIN) {
            return support.error("Refusé : outil d'administration réservé au rôle ADMIN.");
        }
        return null;
    }
}
