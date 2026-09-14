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
import fr.claudegateway.radar.RadarScopeResolver;
import fr.claudegateway.radar.RadarSubjectProjectService;

/**
 * Outil MCP {@code radar_lier_projet} (F-112 / SF-112-06) : lie un sujet du Radar à un projet de la
 * Forge. Relaie {@link RadarSubjectProjectService#link}. Périmètre {@code radar:ecrire}, accès poste
 * par poste, Vigie.
 */
@Component
public class RadarLierProjetTool implements McpToolProvider {

    static final String NAME = "radar_lier_projet";

    private final RadarSubjectProjectService projectService;
    private final RadarScopeResolver scopeResolver;
    private final McpHostAccess hostAccess;
    private final McpToolSupport support;

    public RadarLierProjetTool(RadarSubjectProjectService projectService,
            RadarScopeResolver scopeResolver, McpHostAccess hostAccess, McpToolSupport support) {
        this.projectService = projectService;
        this.scopeResolver = scopeResolver;
        this.hostAccess = hostAccess;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("host_id", McpToolSupport.property("string", "Identifiant (UUID) du poste (Vigie)."));
        properties.put("subject_id", McpToolSupport.property("string", "Identifiant (UUID) du sujet."));
        properties.put("project_id", McpToolSupport.property("string", "Identifiant (UUID) du projet."));
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Lier un sujet à un projet")
                .description("Lie un sujet du Radar à un projet de la Forge. Modifie l'état du Radar.")
                .inputSchema(McpToolSupport.objectSchema(properties,
                        List.of("host_id", "subject_id", "project_id")))
                .annotations(new ToolAnnotations("Lier un sujet à un projet",
                        Boolean.FALSE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE))
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
                    UUID projectId = McpToolSupport.parseUuid(request.arguments().get("project_id"));
                    if (subjectId == null || projectId == null) {
                        return support.error("subject_id et project_id sont requis (UUID attendus).");
                    }
                    try {
                        return support.okUntrusted("Sujet lié au projet.",
                                projectService.link(g.scope(), subjectId, projectId), null);
                    } catch (RuntimeException ex) {
                        return support.error("Liaison impossible : " + ex.getMessage());
                    }
                })
                .build();
    }
}
