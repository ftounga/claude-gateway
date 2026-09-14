package fr.claudegateway.mcp.tool;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import fr.claudegateway.mcp.McpAdminGuard;
import fr.claudegateway.mcp.McpCallContext;
import fr.claudegateway.mcp.McpToolProvider;
import fr.claudegateway.mcp.McpToolSupport;

/**
 * Outil MCP {@code admin_sante} (F-112 / SF-112-07) : l'état applicatif — version servie et
 * disponibilité. Double garde : périmètre {@code admin} <b>et</b> rôle ADMIN.
 *
 * <p>Le détail d'orchestration (pods, déploiement) n'est pas exposé par le backend : il reste à la
 * console d'exploitation. L'outil rend ce que l'application connaît d'elle-même — sa version et son
 * état de marche — sans réimplémenter une supervision (Gateway-First).</p>
 */
@Component
public class AdminSanteTool implements McpToolProvider {

    static final String NAME = "admin_sante";

    private final McpAdminGuard adminGuard;
    private final McpToolSupport support;
    private final String version;

    public AdminSanteTool(McpAdminGuard adminGuard, McpToolSupport support,
            @Value("${app.version:${info.app.version:unknown}}") String version) {
        this.adminGuard = adminGuard;
        this.support = support;
        this.version = version;
    }

    @Override
    public SyncToolSpecification specification() {
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Administration : santé")
                .description("L'état applicatif : version servie, disponibilité, horodatage. Réservé "
                        + "au rôle ADMIN. Le détail des pods et du déploiement reste à la console "
                        + "d'exploitation. Lecture seule.")
                .inputSchema(McpToolSupport.emptySchema())
                .annotations(new ToolAnnotations("Administration : santé",
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
                    Map<String, Object> structured = new LinkedHashMap<>();
                    structured.put("status", "ok");
                    structured.put("version", version);
                    structured.put("checked_at", OffsetDateTime.now().toString());
                    structured.put("note", "Pods et déploiement : voir la console d'exploitation.");
                    return support.ok("Application en marche (version " + version + ").", structured);
                })
                .build();
    }
}
