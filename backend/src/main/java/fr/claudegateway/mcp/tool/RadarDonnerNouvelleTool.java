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
import fr.claudegateway.radar.RadarNewsService;
import fr.claudegateway.radar.RadarScopeResolver;

/**
 * Outil MCP {@code radar_donner_nouvelle} (F-112 / SF-112-06) : verse une nouvelle (une information
 * qu'on connaît hors Teams) dans le Radar. Relaie {@link RadarNewsService#give}. Périmètre
 * {@code radar:ecrire}, accès poste par poste, Vigie.
 */
@Component
public class RadarDonnerNouvelleTool implements McpToolProvider {

    static final String NAME = "radar_donner_nouvelle";

    private final RadarNewsService newsService;
    private final RadarScopeResolver scopeResolver;
    private final McpHostAccess hostAccess;
    private final McpToolSupport support;

    public RadarDonnerNouvelleTool(RadarNewsService newsService, RadarScopeResolver scopeResolver,
            McpHostAccess hostAccess, McpToolSupport support) {
        this.newsService = newsService;
        this.scopeResolver = scopeResolver;
        this.hostAccess = hostAccess;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("host_id", McpToolSupport.property("string", "Identifiant (UUID) du poste (Vigie)."));
        properties.put("text", McpToolSupport.property("string", "La nouvelle à verser au Radar."));
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Donner une nouvelle au Radar")
                .description("Verse une nouvelle (une information connue hors Teams) dans le Radar du "
                        + "poste ; elle est rangée sous le bon sujet. Modifie l'état du Radar.")
                .inputSchema(McpToolSupport.objectSchema(properties, List.of("host_id", "text")))
                .annotations(new ToolAnnotations("Donner une nouvelle au Radar",
                        Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE))
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
                    String text = McpToolSupport.parseString(request.arguments().get("text"));
                    if (text == null) {
                        return support.error("text manquant ou vide.");
                    }
                    try {
                        return support.okUntrusted("Nouvelle versée au Radar.",
                                newsService.give(g.scope(), text), null);
                    } catch (RuntimeException ex) {
                        return support.error("Nouvelle refusée : " + ex.getMessage());
                    }
                })
                .build();
    }
}
