package fr.claudegateway.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import fr.claudegateway.atelier.AtelierMcpTurnLauncher;
import fr.claudegateway.atelier.AtelierMcpTurnLauncher.LaunchResult;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.mcp.McpCallContext;
import fr.claudegateway.mcp.McpHostAccess;
import fr.claudegateway.mcp.McpTerminalAccess;
import fr.claudegateway.mcp.McpToolProvider;
import fr.claudegateway.mcp.McpToolSupport;

/**
 * Outil MCP {@code terminal_ecrire} (F-112 / SF-112-05) : écrit dans le terminal d'un projet, ce qui
 * <b>lance un tour</b> (modèle « tâche » : rend un identifiant tout de suite, la boucle continue en
 * fond). Relaie {@link AtelierMcpTurnLauncher} (réutilise le cœur F-84). Périmètre
 * {@code terminaux:ecrire}, droit terminal, accès poste par poste.
 *
 * <p>Si un tour tourne déjà sur ce projet, l'envoi devient une <b>précision</b> (F-84 §6). La
 * consommation tombe sur le quota de l'utilisateur, comme dans l'application.</p>
 */
@Component
public class TerminalEcrireTool implements McpToolProvider {

    static final String NAME = "terminal_ecrire";

    private final AtelierMcpTurnLauncher launcher;
    private final WorkspaceService workspaceService;
    private final McpTerminalAccess terminalAccess;
    private final McpHostAccess hostAccess;
    private final McpToolSupport support;

    public TerminalEcrireTool(AtelierMcpTurnLauncher launcher, WorkspaceService workspaceService,
            McpTerminalAccess terminalAccess, McpHostAccess hostAccess, McpToolSupport support) {
        this.launcher = launcher;
        this.workspaceService = workspaceService;
        this.terminalAccess = terminalAccess;
        this.hostAccess = hostAccess;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project_id", McpToolSupport.property("string", "Identifiant (UUID) du projet."));
        properties.put("message", McpToolSupport.property("string",
                "La demande à envoyer dans le terminal (lance un tour d'agent)."));
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Écrire dans un terminal")
                .description("Écrit une demande dans le terminal d'un projet, ce qui lance un tour "
                        + "d'agent. Non bloquant : rend un turn_id tout de suite ; suivre avec "
                        + "tour_suivre. Si un tour tourne déjà, le message devient une précision. La "
                        + "consommation tombe sur le quota du compte.")
                .inputSchema(McpToolSupport.objectSchema(properties, List.of("project_id", "message")))
                .annotations(new ToolAnnotations("Écrire dans un terminal",
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
                    LaunchResult result =
                            launcher.launch(ctx.user().id(), projectId, message, ctx.clientLabel());
                    Map<String, Object> structured = new LinkedHashMap<>();
                    structured.put("turn_id", result.turnId().toString());
                    structured.put("steered", result.steered());
                    structured.put("steer_id", result.steerId());
                    structured.put("cursor", 0);
                    String text = result.steered()
                            ? "Un tour tournait déjà : votre message a été ajouté comme précision."
                            : (result.steerRejected()
                                    ? "Un tour tourne déjà et sa file de précisions est pleine ; laissez-le avancer."
                                    : "Tour lancé. Suivez-le avec tour_suivre (turn_id ci-dessus).");
                    return support.ok(text, structured);
                })
                .build();
    }
}
