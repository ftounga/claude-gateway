package fr.claudegateway.mcp.tool;

import java.util.Map;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.mcp.McpAdminGuard;
import fr.claudegateway.mcp.McpCallContext;
import fr.claudegateway.mcp.McpToolProvider;
import fr.claudegateway.mcp.McpToolSupport;

/**
 * Outil MCP {@code admin_utilisateurs} (F-112 / SF-112-07) : liste les utilisateurs. Relaie
 * {@link AdminService#listUsers}. Double garde : périmètre {@code admin} <b>et</b> rôle ADMIN.
 */
@Component
public class AdminUtilisateursTool implements McpToolProvider {

    static final String NAME = "admin_utilisateurs";

    private final AdminService adminService;
    private final McpAdminGuard adminGuard;
    private final McpToolSupport support;

    public AdminUtilisateursTool(AdminService adminService, McpAdminGuard adminGuard,
            McpToolSupport support) {
        this.adminService = adminService;
        this.adminGuard = adminGuard;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Administration : utilisateurs")
                .description("Liste les utilisateurs de la plateforme. Réservé au rôle ADMIN. Lecture seule.")
                .inputSchema(McpToolSupport.emptySchema())
                .annotations(new ToolAnnotations("Administration : utilisateurs",
                        Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    McpCallContext ctx = McpCallContext.require(exchange);
                    CallToolResult denied = adminGuard.check(ctx);
                    if (denied != null) {
                        return denied;
                    }
                    Object users = support.withPrincipal(ctx.user(),
                            () -> support.toJsonTree(adminService.listUsers()));
                    return support.ok("Utilisateurs de la plateforme.", Map.of("utilisateurs", users));
                })
                .build();
    }
}
