package fr.claudegateway.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import fr.claudegateway.runner.update.RunnerUpdateProgress;
import fr.claudegateway.runner.update.RunnerUpdateService;

/**
 * Outil MCP {@code poste_mettre_a_jour_runner} (F-112 / SF-112-04) : demande la mise à jour du runner
 * d'un poste (F-111). Relaie {@link RunnerUpdateService#request} avec l'identité du porteur. Action
 * (annotation destructive, non idempotente), périmètre {@code postes:agir}, accès poste par poste.
 *
 * <p>La demande est <b>non bloquante</b> : elle envoie l'ordre et rend l'état courant de la mise à
 * jour. Le suivi se fait par relecture (poste_detail, poste_verifier) ou l'écran des postes.</p>
 */
@Component
public class PosteMettreAJourRunnerTool implements McpToolProvider {

    static final String NAME = "poste_mettre_a_jour_runner";

    private final RunnerUpdateService updateService;
    private final McpHostAccess hostAccess;
    private final McpToolSupport support;

    public PosteMettreAJourRunnerTool(RunnerUpdateService updateService, McpHostAccess hostAccess,
            McpToolSupport support) {
        this.updateService = updateService;
        this.hostAccess = hostAccess;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("host_id", McpToolSupport.property("string", "Identifiant (UUID) du poste."));
        properties.put("force", McpToolSupport.property("boolean",
                "Facultatif : forcer la mise à jour même si le runner paraît à jour (défaut false)."));
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Mettre à jour le runner d'un poste")
                .description("Demande la mise à jour du runner d'un poste accessible. Non bloquant : "
                        + "envoie l'ordre et rend l'état courant. Action modifiant l'état du poste.")
                .inputSchema(McpToolSupport.objectSchema(properties, List.of("host_id")))
                .annotations(new ToolAnnotations("Mettre à jour le runner d'un poste",
                        Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    McpCallContext ctx = McpCallContext.require(exchange);
                    if (!ctx.hasScope(McpScopes.POSTES_AGIR)) {
                        return support.deniedScope(McpScopes.POSTES_AGIR);
                    }
                    UUID hostId = McpToolSupport.parseUuid(request.arguments().get("host_id"));
                    if (hostId == null) {
                        return support.error("host_id manquant ou invalide (UUID attendu).");
                    }
                    if (!hostAccess.canAccess(ctx, hostId)) {
                        return support.deniedHost();
                    }
                    boolean force = McpToolSupport.parseBoolean(request.arguments().get("force"), false);
                    RunnerUpdateProgress progress;
                    try {
                        progress = updateService.request(ctx.user(), hostId, force);
                    } catch (RuntimeException ex) {
                        return support.error("Mise à jour impossible : " + ex.getMessage());
                    }
                    Map<String, Object> structured = new LinkedHashMap<>();
                    structured.put("host_id", hostId.toString());
                    structured.put("state", progress.state());
                    structured.put("from_version", progress.fromVersion());
                    structured.put("to_version", progress.toVersion());
                    structured.put("forced", progress.forced());
                    structured.put("active", progress.active());
                    return support.ok("Mise à jour demandée (état : " + progress.state() + ").",
                            structured);
                })
                .build();
    }
}
