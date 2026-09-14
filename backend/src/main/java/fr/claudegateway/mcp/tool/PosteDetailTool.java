package fr.claudegateway.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import fr.claudegateway.mcp.McpCallContext;
import fr.claudegateway.mcp.McpHostAccess;
import fr.claudegateway.mcp.McpScopes;
import fr.claudegateway.mcp.McpToolProvider;
import fr.claudegateway.mcp.McpToolSupport;
import fr.claudegateway.runner.RunnerStatusService;
import fr.claudegateway.runner.RunnerStatusService.RunnerStatus;
import fr.claudegateway.runner.host.RunnerHostOverviewService;
import fr.claudegateway.runner.host.dto.RunnerHostOverviewResponse;

/**
 * Outil MCP {@code poste_detail} (F-112 / SF-112-04) : le détail d'un poste — statut daté, version du
 * runner, espaces, projets, mise à jour — pour un poste <b>accessible</b> par le porteur. Relaie
 * {@link RunnerHostOverviewService} et {@link RunnerStatusService}. Lecture seule,
 * périmètre {@code postes:lire}, accès poste par poste.
 */
@Component
public class PosteDetailTool implements McpToolProvider {

    static final String NAME = "poste_detail";

    private final RunnerHostOverviewService overviewService;
    private final RunnerStatusService statusService;
    private final McpHostAccess hostAccess;
    private final McpToolSupport support;

    public PosteDetailTool(RunnerHostOverviewService overviewService, RunnerStatusService statusService,
            McpHostAccess hostAccess, McpToolSupport support) {
        this.overviewService = overviewService;
        this.statusService = statusService;
        this.hostAccess = hostAccess;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("host_id", McpToolSupport.property("string", "Identifiant (UUID) du poste."));
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Détail d'un poste")
                .description("Détaille un poste accessible : statut daté, version du runner, espaces "
                        + "actifs, mise à jour du runner, et les projets rangés dessous. Lecture "
                        + "seule. Ne renvoie aucun contenu de terminal ni aucune commande.")
                .inputSchema(McpToolSupport.objectSchema(properties, List.of("host_id")))
                .annotations(new ToolAnnotations("Détail d'un poste",
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
                    if (hostId == null) {
                        return support.error("host_id manquant ou invalide (UUID attendu).");
                    }
                    if (!hostAccess.canAccess(ctx, hostId)) {
                        return support.deniedHost();
                    }
                    Optional<RunnerHostOverviewResponse> found = overviewService.overview(ctx.user().id())
                            .stream()
                            .filter(h -> hostId.equals(h.id()))
                            .findFirst();
                    if (found.isEmpty()) {
                        return support.error("Poste introuvable.");
                    }
                    Map<String, Object> structured = PosteToolMapper.hostWithProjects(found.get());
                    RunnerStatus status = statusService.hostStatus(ctx.user().id(), hostId);
                    structured.put("paired", status.paired());
                    structured.put("connected_now", status.connected());
                    return support.ok("Poste « " + found.get().name() + " ».", structured);
                })
                .build();
    }
}
