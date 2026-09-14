package fr.claudegateway.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import fr.claudegateway.governance.GovernanceActivationService;
import fr.claudegateway.governance.dto.GovernanceHostSummary;
import fr.claudegateway.mcp.McpCallContext;
import fr.claudegateway.mcp.McpScopes;
import fr.claudegateway.mcp.McpToolProvider;
import fr.claudegateway.mcp.McpToolSupport;

/**
 * Outil MCP {@code gouvernance_etat} (F-112 / SF-112-04) : l'état de gouvernance du compte — les
 * postes gouvernables et, pour chacun, combien de paquets sont actifs et combien attendent une mise
 * à jour. Relaie {@link GovernanceActivationService#hosts}. Lecture seule, périmètre
 * {@code postes:lire}, isolation {@code user_id}.
 */
@Component
public class GouvernanceEtatTool implements McpToolProvider {

    static final String NAME = "gouvernance_etat";

    private final GovernanceActivationService activationService;
    private final McpToolSupport support;

    public GouvernanceEtatTool(GovernanceActivationService activationService, McpToolSupport support) {
        this.activationService = activationService;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Tool tool = Tool.builder()
                .name(NAME)
                .title("État de la gouvernance")
                .description("Liste les postes gouvernables du compte connecté et, pour chacun, le "
                        + "nombre de paquets de gouvernance actifs et le nombre de mises à jour en "
                        + "attente. Lecture seule, sans effet.")
                .inputSchema(McpToolSupport.emptySchema())
                .annotations(new ToolAnnotations("État de la gouvernance",
                        Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    McpCallContext ctx = McpCallContext.require(exchange);
                    if (!ctx.hasScope(McpScopes.POSTES_LIRE)) {
                        return support.deniedScope(McpScopes.POSTES_LIRE);
                    }
                    List<Map<String, Object>> hosts = activationService.hosts(ctx.user().id())
                            .stream()
                            .map(this::host)
                            .toList();
                    Map<String, Object> structured = new LinkedHashMap<>();
                    structured.put("count", hosts.size());
                    structured.put("postes", hosts);
                    return support.ok(hosts.size() + " poste(s) gouvernable(s).", structured);
                })
                .build();
    }

    private Map<String, Object> host(GovernanceHostSummary summary) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ref", summary.ref());
        map.put("id", summary.id() == null ? null : summary.id().toString());
        map.put("name", summary.name());
        map.put("virtual", summary.virtual());
        map.put("projects", summary.projects());
        map.put("active_packages", summary.active());
        map.put("updates_pending", summary.outdated());
        return map;
    }
}
