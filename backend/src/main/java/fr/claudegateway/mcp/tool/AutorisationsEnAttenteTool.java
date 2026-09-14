package fr.claudegateway.mcp.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.atelier.live.PendingApproval;
import fr.claudegateway.mcp.McpCallContext;
import fr.claudegateway.mcp.McpResourceProperties;
import fr.claudegateway.mcp.McpScopes;
import fr.claudegateway.mcp.McpTerminalAccess;
import fr.claudegateway.mcp.McpToolProvider;
import fr.claudegateway.mcp.McpToolSupport;

/**
 * Outil MCP {@code autorisations_en_attente} (F-112 / SF-112-05) : <b>liste</b> les autorisations en
 * attente sur les tours vivants du compte (porte de confirmation ADR-019, écritures M365 F-108), avec
 * le <b>lien direct</b> vers l'application pour valider.
 *
 * <p><b>Garde §6.1 — une IA ne s'autorise jamais elle-même.</b> Cet outil ne fait que lister : aucun
 * champ, aucun paramètre n'accorde une autorisation. La validation reste un geste humain dans
 * l'application. Aucun outil MCP n'expose la porte de confirmation.</p>
 */
@Component
public class AutorisationsEnAttenteTool implements McpToolProvider {

    static final String NAME = "autorisations_en_attente";

    private final LiveTurnRegistry liveTurns;
    private final McpTerminalAccess terminalAccess;
    private final McpResourceProperties resourceProperties;
    private final McpToolSupport support;

    public AutorisationsEnAttenteTool(LiveTurnRegistry liveTurns, McpTerminalAccess terminalAccess,
            McpResourceProperties resourceProperties, McpToolSupport support) {
        this.liveTurns = liveTurns;
        this.terminalAccess = terminalAccess;
        this.resourceProperties = resourceProperties;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Autorisations en attente")
                .description("Liste les autorisations en attente sur les tours en cours du compte "
                        + "(commande sur la machine, écriture Microsoft 365), avec le lien direct "
                        + "pour valider dans l'application. Une IA n'accorde JAMAIS une autorisation : "
                        + "cet outil ne fait que lister. Lecture seule.")
                .inputSchema(McpToolSupport.emptySchema())
                .annotations(new ToolAnnotations("Autorisations en attente",
                        Boolean.TRUE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    McpCallContext ctx = McpCallContext.require(exchange);
                    if (!ctx.hasScope(McpScopes.TERMINAUX_ECRIRE)) {
                        return support.deniedScope(McpScopes.TERMINAUX_ECRIRE);
                    }
                    if (!terminalAccess.hasForgeAccess(ctx.user())) {
                        return support.error(
                                "Accès terminal refusé : ce compte n'a pas le droit Forge requis.");
                    }
                    List<Map<String, Object>> pending = new ArrayList<>();
                    for (LiveTurn turn : liveTurns.liveTurnsOf(ctx.user().id())) {
                        turn.pendingApproval()
                                .filter(PendingApproval::stillOpen)
                                .ifPresent(approval -> pending.add(entry(turn, approval)));
                    }
                    Map<String, Object> structured = new LinkedHashMap<>();
                    structured.put("count", pending.size());
                    structured.put("pending", pending);
                    structured.put("note", "Une IA n'accorde jamais une autorisation. Ouvrez le lien "
                            + "pour valider dans l'application.");
                    return support.ok(pending.size() + " autorisation(s) en attente.", structured);
                })
                .build();
    }

    private Map<String, Object> entry(LiveTurn turn, PendingApproval approval) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("project_id", turn.workspaceId().toString());
        map.put("turn_id", turn.turnId().toString());
        map.put("tool", approval.tool());
        map.put("detail", approval.detail());
        map.put("remaining_ms", approval.remainingMs());
        map.put("app_link", appBase() + "/atelier/" + turn.workspaceId());
        map.put("grantable_here", false);
        return map;
    }

    /** Base de l'application, dérivée de l'URL de la ressource MCP ({@code …/api/mcp}). */
    private String appBase() {
        String resource = resourceProperties.resource();
        if (resource == null) {
            return "";
        }
        int idx = resource.indexOf("/api/mcp");
        if (idx > 0) {
            return resource.substring(0, idx);
        }
        return resource;
    }
}
