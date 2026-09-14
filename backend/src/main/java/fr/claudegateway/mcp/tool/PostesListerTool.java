package fr.claudegateway.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import fr.claudegateway.mcp.McpCallContext;
import fr.claudegateway.mcp.McpScopes;
import fr.claudegateway.mcp.McpToolProvider;
import fr.claudegateway.mcp.McpToolSupport;
import fr.claudegateway.runner.host.RunnerHostOverviewService;

/**
 * Outil MCP {@code postes_lister} (F-112 / SF-112-04) : liste les postes du compte connecté, leur
 * état, leurs espaces et l'état de leur runner. Relaie {@link RunnerHostOverviewService#overview}
 * (Gateway-First). Lecture seule, périmètre {@code postes:lire}, isolation {@code user_id}.
 */
@Component
public class PostesListerTool implements McpToolProvider {

    static final String NAME = "postes_lister";

    private final RunnerHostOverviewService overviewService;
    private final McpToolSupport support;

    public PostesListerTool(RunnerHostOverviewService overviewService, McpToolSupport support) {
        this.overviewService = overviewService;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Lister mes postes")
                .description("Liste les postes du compte connecté avec leur statut (connecté, "
                        + "version du runner, espaces actifs, mise à jour disponible) et le nombre "
                        + "de projets. Lecture seule, sans effet. Ne renvoie aucun contenu de "
                        + "terminal ni aucune commande.")
                .inputSchema(McpToolSupport.emptySchema())
                .annotations(new ToolAnnotations("Lister mes postes",
                        Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    McpCallContext ctx = McpCallContext.require(exchange);
                    if (!ctx.hasScope(McpScopes.POSTES_LIRE)) {
                        return support.deniedScope(McpScopes.POSTES_LIRE);
                    }
                    List<Map<String, Object>> postes = overviewService.overview(ctx.user().id())
                            .stream()
                            .map(PosteToolMapper::host)
                            .toList();
                    Map<String, Object> structured = new LinkedHashMap<>();
                    structured.put("count", postes.size());
                    structured.put("postes", postes);
                    return support.ok(postes.size() + " poste(s).", structured);
                })
                .build();
    }
}
