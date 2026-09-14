package fr.claudegateway.mcp.tool;

import java.util.Map;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import fr.claudegateway.mcp.McpCallContext;
import fr.claudegateway.mcp.McpScopes;
import fr.claudegateway.mcp.McpToolProvider;
import fr.claudegateway.mcp.McpToolSupport;
import fr.claudegateway.quota.UsageReportService;

/**
 * Outil MCP {@code compte_consommation} (F-112 / SF-112-07) : la consommation du compte connecté.
 * Relaie {@link UsageReportService#buildReport}. Périmètre {@code compte:lire}, isolation {@code user_id}.
 */
@Component
public class CompteConsommationTool implements McpToolProvider {

    static final String NAME = "compte_consommation";

    private final UsageReportService usageReportService;
    private final McpToolSupport support;

    public CompteConsommationTool(UsageReportService usageReportService, McpToolSupport support) {
        this.usageReportService = usageReportService;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Ma consommation")
                .description("La consommation du compte connecté (quota, usage courant). Lecture seule.")
                .inputSchema(McpToolSupport.emptySchema())
                .annotations(new ToolAnnotations("Ma consommation",
                        Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    McpCallContext ctx = McpCallContext.require(exchange);
                    if (!ctx.hasScope(McpScopes.COMPTE_LIRE)) {
                        return support.deniedScope(McpScopes.COMPTE_LIRE);
                    }
                    Object report = support.toJsonTree(usageReportService.buildReport(ctx.user().id()));
                    return support.ok("Consommation du compte.", Map.of("consommation", report));
                })
                .build();
    }
}
