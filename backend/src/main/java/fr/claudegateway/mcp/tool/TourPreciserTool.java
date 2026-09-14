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

import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.atelier.live.RemoteTurnSource;
import fr.claudegateway.atelier.live.SteerReceipt;
import fr.claudegateway.atelier.live.TurnSteering;
import fr.claudegateway.mcp.McpCallContext;
import fr.claudegateway.mcp.McpHostAccess;
import fr.claudegateway.mcp.McpTerminalAccess;
import fr.claudegateway.mcp.McpToolProvider;
import fr.claudegateway.mcp.McpToolSupport;

/**
 * Outil MCP {@code tour_preciser} (F-112 / SF-112-05) : dépose une <b>précision</b> dans le tour
 * vivant d'un projet (F-84 / SF-84-06) — la boucle la lit au début de l'étape suivante. Relaie
 * {@link TurnSteering}. Périmètre {@code terminaux:ecrire}, accès poste par poste.
 */
@Component
public class TourPreciserTool implements McpToolProvider {

    static final String NAME = "tour_preciser";

    private final LiveTurnRegistry liveTurns;
    private final RemoteTurnSource remoteTurns;
    private final WorkspaceService workspaceService;
    private final McpTerminalAccess terminalAccess;
    private final McpHostAccess hostAccess;
    private final McpToolSupport support;

    public TourPreciserTool(LiveTurnRegistry liveTurns, RemoteTurnSource remoteTurns,
            WorkspaceService workspaceService, McpTerminalAccess terminalAccess,
            McpHostAccess hostAccess, McpToolSupport support) {
        this.liveTurns = liveTurns;
        this.remoteTurns = remoteTurns;
        this.workspaceService = workspaceService;
        this.terminalAccess = terminalAccess;
        this.hostAccess = hostAccess;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project_id", McpToolSupport.property("string", "Identifiant (UUID) du projet."));
        properties.put("message", McpToolSupport.property("string", "La précision à déposer."));
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Préciser un tour")
                .description("Dépose une précision dans le tour en cours d'un projet ; la boucle la "
                        + "lit à l'étape suivante. Erreur nommée s'il n'y a pas de tour vivant ou si "
                        + "la file de précisions est pleine.")
                .inputSchema(McpToolSupport.objectSchema(properties, List.of("project_id", "message")))
                .annotations(new ToolAnnotations("Préciser un tour",
                        Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    McpCallContext ctx = McpCallContext.require(exchange);
                    UUID projectId = McpToolSupport.parseUuid(request.arguments().get("project_id"));
                    TerminalToolSupport.Guarded guarded = TerminalToolSupport.resolve(
                            ctx, projectId, workspaceService, terminalAccess, hostAccess, support);
                    if (guarded.error() != null) {
                        return guarded.error();
                    }
                    String message = McpToolSupport.parseString(request.arguments().get("message"));
                    if (message == null) {
                        return support.error("message manquant ou vide.");
                    }
                    Optional<SteerReceipt> receipt = new TurnSteering(liveTurns, remoteTurns)
                            .steer(ctx.user().id(), projectId, message);
                    if (receipt.isEmpty()) {
                        return support.error("Aucun tour vivant : envoyez ce message avec "
                                + "terminal_ecrire pour lancer un nouveau tour.");
                    }
                    if (!receipt.get().accepted()) {
                        return support.error("Trop de précisions en attente ; laissez le tour avancer.");
                    }
                    Map<String, Object> structured = new LinkedHashMap<>();
                    structured.put("steer_id", receipt.get().steerId());
                    structured.put("turn_id", receipt.get().turnId() == null
                            ? null : receipt.get().turnId().toString());
                    return support.ok("Précision déposée.", structured);
                })
                .build();
    }
}
