package fr.claudegateway.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import fr.claudegateway.governance.GovernanceActivationService;
import fr.claudegateway.governance.GovernanceHostRef;
import fr.claudegateway.governance.dto.GovernanceActivationView;
import fr.claudegateway.governance.dto.GovernanceHostProjectView;
import fr.claudegateway.governance.dto.GovernanceHostView;
import fr.claudegateway.governance.dto.GovernancePackageView;
import fr.claudegateway.mcp.McpCallContext;
import fr.claudegateway.mcp.McpHostAccess;
import fr.claudegateway.mcp.McpScopes;
import fr.claudegateway.mcp.McpToolProvider;
import fr.claudegateway.mcp.McpToolSupport;

/**
 * Outil MCP {@code carte_lire} (F-112 / SF-112-04) : la carte de gouvernance d'un poste — ce qui s'y
 * applique, ce qui pourrait s'y appliquer, et les projets concernés. Relaie
 * {@link GovernanceActivationService#describe}. Lecture seule, périmètre {@code postes:lire}, accès
 * poste par poste.
 */
@Component
public class CarteLireTool implements McpToolProvider {

    static final String NAME = "carte_lire";

    private final GovernanceActivationService activationService;
    private final McpHostAccess hostAccess;
    private final McpToolSupport support;

    public CarteLireTool(GovernanceActivationService activationService, McpHostAccess hostAccess,
            McpToolSupport support) {
        this.activationService = activationService;
        this.hostAccess = hostAccess;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("host_id", McpToolSupport.property("string", "Identifiant (UUID) du poste."));
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Carte de gouvernance d'un poste")
                .description("Lit la carte de gouvernance d'un poste accessible : paquets actifs, "
                        + "paquets disponibles et projets concernés. Lecture seule, sans effet.")
                .inputSchema(McpToolSupport.objectSchema(properties, List.of("host_id")))
                .annotations(new ToolAnnotations("Carte de gouvernance d'un poste",
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
                    GovernanceHostView view;
                    try {
                        view = activationService.describe(ctx.user().id(), GovernanceHostRef.of(hostId));
                    } catch (RuntimeException ex) {
                        return support.error("Poste introuvable.");
                    }
                    return support.ok("Carte de gouvernance de « " + view.name() + " ».", map(view));
                })
                .build();
    }

    private Map<String, Object> map(GovernanceHostView view) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ref", view.ref());
        map.put("id", view.id() == null ? null : view.id().toString());
        map.put("name", view.name());
        map.put("virtual", view.virtual());
        map.put("projects", view.projects().stream().map(this::project).toList());
        map.put("active_packages", view.active().stream().map(this::activePackage).toList());
        map.put("available_packages", view.available().stream().map(this::pkg).toList());
        return map;
    }

    private Map<String, Object> project(GovernanceHostProjectView project) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", project.id() == null ? null : project.id().toString());
        map.put("name", project.name());
        map.put("path", project.path());
        return map;
    }

    private Map<String, Object> activePackage(GovernanceActivationView activation) {
        Map<String, Object> map = pkg(activation.pkg());
        map.put("applied_version", activation.appliedVersion());
        return map;
    }

    private Map<String, Object> pkg(GovernancePackageView pkg) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", pkg.id() == null ? null : pkg.id().toString());
        map.put("slug", pkg.slug());
        map.put("name", pkg.name());
        map.put("summary", pkg.summary());
        map.put("version", pkg.version());
        return map;
    }
}
