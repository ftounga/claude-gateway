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
import fr.claudegateway.runner.RunnerStatusService;
import fr.claudegateway.runner.RunnerStatusService.RunnerStatus;

/**
 * Outil MCP {@code projet_detail} (F-112 / SF-112-04) : le détail d'un projet possédé — poste, cible
 * d'exécution, statut runner. Relaie {@link WorkspaceService#requireOwned} et
 * {@link RunnerStatusService#status}. Lecture seule, périmètre {@code postes:lire}.
 */
@Component
public class ProjetDetailTool implements McpToolProvider {

    static final String NAME = "projet_detail";

    private final WorkspaceService workspaceService;
    private final RunnerStatusService statusService;
    private final McpHostAccess hostAccess;
    private final McpToolSupport support;

    public ProjetDetailTool(WorkspaceService workspaceService, RunnerStatusService statusService,
            McpHostAccess hostAccess, McpToolSupport support) {
        this.workspaceService = workspaceService;
        this.statusService = statusService;
        this.hostAccess = hostAccess;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project_id", McpToolSupport.property("string", "Identifiant (UUID) du projet."));
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Détail d'un projet")
                .description("Détaille un projet possédé : poste de rattachement, cible d'exécution "
                        + "et statut du runner. Lecture seule, sans effet.")
                .inputSchema(McpToolSupport.objectSchema(properties, List.of("project_id")))
                .annotations(new ToolAnnotations("Détail d'un projet",
                        Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    McpCallContext ctx = McpCallContext.require(exchange);
                    if (!ctx.hasScope(McpScopes.POSTES_LIRE)) {
                        return support.deniedScope(McpScopes.POSTES_LIRE);
                    }
                    UUID projectId = McpToolSupport.parseUuid(request.arguments().get("project_id"));
                    if (projectId == null) {
                        return support.error("project_id manquant ou invalide (UUID attendu).");
                    }
                    Workspace workspace;
                    try {
                        workspace = workspaceService.requireOwned(ctx.user().id(), projectId);
                    } catch (RuntimeException ex) {
                        return support.error("Projet introuvable.");
                    }
                    // Accès poste par poste : un projet rattaché à un poste non accessible est refusé.
                    if (workspace.getHostId() != null && !hostAccess.canAccess(ctx, workspace.getHostId())) {
                        return support.deniedHost();
                    }
                    Map<String, Object> structured = new LinkedHashMap<>();
                    structured.put("id", workspace.getId().toString());
                    structured.put("name", workspace.getName());
                    structured.put("host_id",
                            workspace.getHostId() == null ? null : workspace.getHostId().toString());
                    structured.put("project_path", workspace.getProjectPath());
                    structured.put("execution_target", workspace.getExecutionTarget() == null
                            ? null : workspace.getExecutionTarget().name());
                    RunnerStatus status = statusService.status(ctx.user().id(), projectId);
                    structured.put("connected", status.connected());
                    structured.put("paired", status.paired());
                    return support.ok("Projet « " + workspace.getName() + " ».", structured);
                })
                .build();
    }
}
