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
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarScopeResolver;
import fr.claudegateway.radar.RadarSync;
import fr.claudegateway.radar.RadarSyncTrigger;
import fr.claudegateway.radar.sync.RadarSyncLauncher;
import fr.claudegateway.runner.RunnerLiveness;

/**
 * Outil MCP {@code radar_synchroniser} (F-112 / SF-112-06) : lance une synchronisation du Radar sur le
 * poste (« Synchroniser maintenant », SF-100-02), en <b>tâche</b> — rend un {@code sync_id} tout de
 * suite, la synchro tourne sur la machine. Relaie {@link RadarSyncLauncher#start}. Périmètre
 * {@code radar:ecrire}, accès poste par poste, Vigie. Poste hors ligne : rien n'est créé.
 */
@Component
public class RadarSynchroniserTool implements McpToolProvider {

    static final String NAME = "radar_synchroniser";

    private final RadarSyncLauncher launcher;
    private final RunnerLiveness liveness;
    private final RadarScopeResolver scopeResolver;
    private final McpHostAccess hostAccess;
    private final McpToolSupport support;

    public RadarSynchroniserTool(RadarSyncLauncher launcher, RunnerLiveness liveness,
            RadarScopeResolver scopeResolver, McpHostAccess hostAccess, McpToolSupport support) {
        this.launcher = launcher;
        this.liveness = liveness;
        this.scopeResolver = scopeResolver;
        this.hostAccess = hostAccess;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("host_id", McpToolSupport.property("string", "Identifiant (UUID) du poste (Vigie)."));
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Synchroniser le Radar")
                .description("Lance une synchronisation du Radar sur le poste (la lecture Teams du "
                        + "soir). Non bloquant : rend un sync_id tout de suite ; suivre avec "
                        + "radar_couverture. Poste hors ligne : rien n'est lancé.")
                .inputSchema(McpToolSupport.objectSchema(properties, List.of("host_id")))
                .annotations(new ToolAnnotations("Synchroniser le Radar",
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
                    RadarScope scope = g.scope();
                    if (!liveness.isAlive(scope.userId(), scope.hostId())) {
                        return support.error("Poste hors ligne : lancez le runner, puis recommencez.");
                    }
                    RadarSync sync = launcher.start(scope, RadarSyncTrigger.MANUAL, null);
                    Map<String, Object> structured = new LinkedHashMap<>();
                    structured.put("sync_id", sync.getId().toString());
                    structured.put("trigger", sync.getTriggerKind() == null
                            ? null : sync.getTriggerKind().name());
                    structured.put("started_at", sync.getStartedAt() == null
                            ? null : sync.getStartedAt().toString());
                    return support.ok("Synchronisation lancée (tâche).", structured);
                })
                .build();
    }
}
