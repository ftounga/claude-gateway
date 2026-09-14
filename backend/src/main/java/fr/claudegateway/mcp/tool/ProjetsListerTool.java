package fr.claudegateway.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.mcp.McpCallContext;
import fr.claudegateway.mcp.McpHostAccess;
import fr.claudegateway.mcp.McpScopes;
import fr.claudegateway.mcp.McpToolProvider;
import fr.claudegateway.mcp.McpToolSupport;

/**
 * Outil MCP {@code projets_lister} (F-112 / SF-112-04) : liste les projets (Forge) du compte, tous
 * postes confondus, ou ceux d'un poste précis si {@code host_id} est fourni. Relaie
 * {@link WorkspaceService}. Lecture seule, périmètre {@code postes:lire}, isolation {@code user_id}.
 */
@Component
public class ProjetsListerTool implements McpToolProvider {

    static final String NAME = "projets_lister";

    private final WorkspaceService workspaceService;
    private final McpHostAccess hostAccess;
    private final McpToolSupport support;

    public ProjetsListerTool(WorkspaceService workspaceService, McpHostAccess hostAccess,
            McpToolSupport support) {
        this.workspaceService = workspaceService;
        this.hostAccess = hostAccess;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("host_id", McpToolSupport.property("string",
                "Facultatif : identifiant (UUID) d'un poste pour ne lister que ses projets."));
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Lister mes projets")
                .description("Liste les projets de la Forge du compte connecté, avec leur poste et "
                        + "leur cible d'exécution. Restreint à un poste si host_id est fourni. "
                        + "Lecture seule, sans effet.")
                .inputSchema(McpToolSupport.objectSchema(properties, List.of()))
                .annotations(new ToolAnnotations("Lister mes projets",
                        Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    McpCallContext ctx = McpCallContext.require(exchange);
                    if (!ctx.hasScope(McpScopes.POSTES_LIRE)) {
                        return support.deniedScope(McpScopes.POSTES_LIRE);
                    }
                    UUID hostId = McpToolSupport.parseUuid(request.arguments().get("host_id"));
                    List<Workspace> workspaces;
                    if (hostId != null) {
                        if (!hostAccess.canAccess(ctx, hostId)) {
                            return support.deniedHost();
                        }
                        workspaces = workspaceService.listByHost(ctx.user().id(), hostId);
                    } else {
                        workspaces = workspaceService.list(ctx.user().id());
                    }
                    List<Map<String, Object>> projets = workspaces.stream().map(this::project).toList();
                    Map<String, Object> structured = new LinkedHashMap<>();
                    structured.put("count", projets.size());
                    structured.put("projets", projets);
                    return support.ok(projets.size() + " projet(s).", structured);
                })
                .build();
    }

    private Map<String, Object> project(Workspace workspace) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", workspace.getId().toString());
        map.put("name", workspace.getName());
        map.put("host_id", workspace.getHostId() == null ? null : workspace.getHostId().toString());
        map.put("project_path", workspace.getProjectPath());
        map.put("execution_target",
                workspace.getExecutionTarget() == null ? null : workspace.getExecutionTarget().name());
        map.put("created_at", workspace.getCreatedAt() == null ? null : workspace.getCreatedAt().toString());
        return map;
    }
}
