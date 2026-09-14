package fr.claudegateway.mcp.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import fr.claudegateway.runner.RunnerStatusService;
import fr.claudegateway.runner.RunnerStatusService.RunnerStatus;
import fr.claudegateway.runner.host.RunnerHostOverviewService;
import fr.claudegateway.runner.host.dto.RunnerHostOverviewResponse;

/**
 * Outil MCP {@code poste_verifier} (F-112 / SF-112-04) : un <b>verdict de santé</b> d'un poste —
 * appairage, connectivité, version du runner — agrégé en lecture, <b>sans aller-retour vers la
 * machine</b> (garde « pas de traitement lourd synchrone »). Relaie
 * {@link RunnerHostOverviewService} et {@link RunnerStatusService}. Périmètre {@code postes:lire},
 * accès poste par poste.
 *
 * <p>Note : la vérification guidée Teams (F-100), qui déclenche un aller-retour runner, relève de la
 * Vigie et n'est pas exposée ici. La mise à jour du runner (action) est
 * {@code poste_mettre_a_jour_runner}.</p>
 */
@Component
public class PosteVerifierTool implements McpToolProvider {

    static final String NAME = "poste_verifier";

    private final RunnerHostOverviewService overviewService;
    private final RunnerStatusService statusService;
    private final McpHostAccess hostAccess;
    private final McpToolSupport support;

    public PosteVerifierTool(RunnerHostOverviewService overviewService, RunnerStatusService statusService,
            McpHostAccess hostAccess, McpToolSupport support) {
        this.overviewService = overviewService;
        this.statusService = statusService;
        this.hostAccess = hostAccess;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("host_id", McpToolSupport.property("string", "Identifiant (UUID) du poste."));
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Vérifier un poste")
                .description("Rend un verdict de santé d'un poste accessible : est-il appairé, "
                        + "joignable maintenant, son runner est-il à jour. Lecture seule, sans "
                        + "aller-retour vers la machine. Pour appliquer une mise à jour, utiliser "
                        + "poste_mettre_a_jour_runner.")
                .inputSchema(McpToolSupport.objectSchema(properties, List.of("host_id")))
                .annotations(new ToolAnnotations("Vérifier un poste",
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
                    Optional<RunnerHostOverviewResponse> found = overviewService.overview(ctx.user().id())
                            .stream().filter(h -> hostId.equals(h.id())).findFirst();
                    if (found.isEmpty()) {
                        return support.error("Poste introuvable.");
                    }
                    RunnerHostOverviewResponse host = found.get();
                    RunnerStatus status = statusService.hostStatus(ctx.user().id(), hostId);
                    boolean updateRequired =
                            host.runnerUpdate() != null && host.runnerUpdate().required();

                    List<Map<String, Object>> checks = new ArrayList<>();
                    checks.add(check("appairé", status.paired(),
                            status.paired() ? "Le poste a un runner appairé."
                                    : "Aucun runner appairé sur ce poste."));
                    checks.add(check("joignable", status.connected(),
                            status.connected() ? "Le runner répond."
                                    : "Le runner n'est pas joignable maintenant."));
                    checks.add(check("runner_à_jour", !updateRequired,
                            updateRequired ? "Une mise à jour du runner est requise."
                                    : "Le runner n'a pas de mise à jour requise."));

                    boolean healthy = checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("ok")));
                    Map<String, Object> structured = new LinkedHashMap<>();
                    structured.put("host_id", hostId.toString());
                    structured.put("name", host.name());
                    structured.put("healthy", healthy);
                    structured.put("checks", checks);
                    structured.put("runner_update_status",
                            host.runnerUpdate() == null ? null : host.runnerUpdate().status());
                    return support.ok(healthy ? "Poste sain." : "Poste avec des points d'attention.",
                            structured);
                })
                .build();
    }

    private Map<String, Object> check(String name, boolean ok, String detail) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("ok", ok);
        map.put("detail", detail);
        return map;
    }
}
