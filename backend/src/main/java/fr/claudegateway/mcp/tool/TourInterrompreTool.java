package fr.claudegateway.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import fr.claudegateway.atelier.AtelierChatService;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.mcp.McpCallContext;
import fr.claudegateway.mcp.McpHostAccess;
import fr.claudegateway.mcp.McpTerminalAccess;
import fr.claudegateway.mcp.McpToolProvider;
import fr.claudegateway.mcp.McpToolSupport;

/**
 * Outil MCP {@code tour_interrompre} (F-112 / SF-112-05) : interrompt le tour en cours d'un projet
 * (F-38 / SF-38-07). Relaie {@link AtelierChatService#interruptChat}. Périmètre
 * {@code terminaux:ecrire}, accès poste par poste. Idempotent : interrompre alors que rien ne tourne
 * n'est pas une erreur.
 */
@Component
public class TourInterrompreTool implements McpToolProvider {

    static final String NAME = "tour_interrompre";

    private final AtelierChatService chatService;
    private final WorkspaceService workspaceService;
    private final McpTerminalAccess terminalAccess;
    private final McpHostAccess hostAccess;
    private final McpToolSupport support;

    public TourInterrompreTool(AtelierChatService chatService, WorkspaceService workspaceService,
            McpTerminalAccess terminalAccess, McpHostAccess hostAccess, McpToolSupport support) {
        this.chatService = chatService;
        this.workspaceService = workspaceService;
        this.terminalAccess = terminalAccess;
        this.hostAccess = hostAccess;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project_id", McpToolSupport.property("string", "Identifiant (UUID) du projet."));
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Interrompre un tour")
                .description("Interrompt le tour en cours d'un projet : la commande éventuellement "
                        + "lancée sur la machine est tuée, la boucle s'arrête à la frontière sûre "
                        + "suivante. Idempotent.")
                .inputSchema(McpToolSupport.objectSchema(properties, List.of("project_id")))
                .annotations(new ToolAnnotations("Interrompre un tour",
                        Boolean.FALSE, Boolean.TRUE, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE))
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
                    chatService.interruptChat(ctx.user().id(), projectId);
                    return support.ok("Interruption demandée.",
                            Map.of("project_id", projectId.toString(), "interrupted", true));
                })
                .build();
    }
}
