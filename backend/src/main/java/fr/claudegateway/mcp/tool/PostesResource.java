package fr.claudegateway.mcp.tool;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;

import fr.claudegateway.mcp.McpCallContext;
import fr.claudegateway.mcp.McpScopes;
import fr.claudegateway.mcp.McpResourceProvider;
import fr.claudegateway.mcp.McpSecretFilter;
import fr.claudegateway.runner.host.RunnerHostOverviewService;

/**
 * Ressource MCP {@code cg://postes} (F-112 / SF-112-08) : la vue d'ensemble des postes du compte
 * connecté, en JSON, lisible <b>par référence</b> (à attacher au contexte d'une IA). Relaie
 * {@link RunnerHostOverviewService}. Périmètre {@code postes:lire}, isolation {@code user_id} depuis
 * {@link McpCallContext}, secrets masqués.
 */
@Component
public class PostesResource implements McpResourceProvider {

    static final String URI = "cg://postes";

    private final RunnerHostOverviewService overviewService;
    private final McpSecretFilter secretFilter;
    private final ObjectMapper objectMapper;

    public PostesResource(RunnerHostOverviewService overviewService, McpSecretFilter secretFilter,
            ObjectMapper objectMapper) {
        this.overviewService = overviewService;
        this.secretFilter = secretFilter;
        this.objectMapper = objectMapper;
    }

    @Override
    public SyncResourceSpecification specification() {
        Resource resource = Resource.builder()
                .uri(URI)
                .name("Mes postes")
                .description("La vue d'ensemble des postes du compte (statut, runner, projets), en "
                        + "JSON, à lire par référence. Nécessite le périmètre postes:lire.")
                .mimeType("application/json")
                .build();

        return new SyncResourceSpecification(resource, (exchange, request) -> {
            McpCallContext ctx = McpCallContext.require(exchange);
            if (!ctx.hasScope(McpScopes.POSTES_LIRE)) {
                throw new IllegalStateException("Périmètre postes:lire requis pour lire cette ressource.");
            }
            List<Map<String, Object>> postes = overviewService.overview(ctx.user().id())
                    .stream().map(PosteToolMapper::host).toList();
            String json = write(secretFilter.mask(Map.of("count", postes.size(), "postes", postes)));
            return new ReadResourceResult(List.of(
                    new TextResourceContents(URI, "application/json", json)));
        });
    }

    private String write(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Sérialisation de la ressource impossible", ex);
        }
    }
}
