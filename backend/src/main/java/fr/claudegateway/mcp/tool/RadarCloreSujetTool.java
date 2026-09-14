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
import fr.claudegateway.radar.RadarClosureService;
import fr.claudegateway.radar.RadarScopeResolver;

/**
 * Outil MCP {@code radar_clore_sujet} (F-112 / SF-112-06) : clôt un sujet du Radar. Relaie
 * {@link RadarClosureService#close}. Périmètre {@code radar:ecrire}, accès poste par poste, Vigie.
 */
@Component
public class RadarCloreSujetTool implements McpToolProvider {

    static final String NAME = "radar_clore_sujet";

    private final RadarClosureService closureService;
    private final RadarScopeResolver scopeResolver;
    private final McpHostAccess hostAccess;
    private final McpToolSupport support;

    public RadarCloreSujetTool(RadarClosureService closureService, RadarScopeResolver scopeResolver,
            McpHostAccess hostAccess, McpToolSupport support) {
        this.closureService = closureService;
        this.scopeResolver = scopeResolver;
        this.hostAccess = hostAccess;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("host_id", McpToolSupport.property("string", "Identifiant (UUID) du poste (Vigie)."));
        properties.put("subject_id", McpToolSupport.property("string", "Identifiant (UUID) du sujet."));
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Clore un sujet")
                .description("Clôt un sujet du Radar : il se range sans disparaître. Modifie l'état "
                        + "du Radar.")
                .inputSchema(McpToolSupport.objectSchema(properties, List.of("host_id", "subject_id")))
                .annotations(new ToolAnnotations("Clore un sujet",
                        Boolean.FALSE, Boolean.TRUE, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    McpCallContext ctx = McpCallContext.require(exchange);
                    UUID hostId = McpToolSupport.parseUuid(request.arguments().get("host_id"));
                    RadarToolSupport.Guarded g = RadarToolSupport.resolve(
                            ctx, McpScopes.RADAR_ECRIRE, hostId, scopeResolver, hostAccess, support);
                    if (g.error() != null) {
                        return g.error();
                    }
                    UUID subjectId = McpToolSupport.parseUuid(request.arguments().get("subject_id"));
                    if (subjectId == null) {
                        return support.error("subject_id manquant ou invalide (UUID attendu).");
                    }
                    try {
                        return support.okUntrusted("Sujet clos.",
                                closureService.close(g.scope(), subjectId), null);
                    } catch (RuntimeException ex) {
                        return support.error("Clôture impossible : " + ex.getMessage());
                    }
                })
                .build();
    }
}
