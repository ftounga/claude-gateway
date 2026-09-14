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
import fr.claudegateway.pages.Page;
import fr.claudegateway.pages.PageService;
import fr.claudegateway.pages.PageSpace;

/**
 * Outil MCP {@code pages_lister} (F-112 / SF-112-07) : liste les pages d'un espace (et d'un poste).
 * Relaie {@link PageService#list}. Périmètre {@code pages}, accès poste par poste si un poste est visé.
 */
@Component
public class PagesListerTool implements McpToolProvider {

    static final String NAME = "pages_lister";

    private final PageService pageService;
    private final McpHostAccess hostAccess;
    private final McpToolSupport support;

    public PagesListerTool(PageService pageService, McpHostAccess hostAccess, McpToolSupport support) {
        this.pageService = pageService;
        this.hostAccess = hostAccess;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("space", McpToolSupport.property("string", "Espace : FORGE (défaut) ou VIGIE."));
        properties.put("host_id", McpToolSupport.property("string",
                "Poste (UUID), facultatif — sinon les pages sans poste."));
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Lister les pages")
                .description("Liste les pages publiées d'un espace (FORGE ou VIGIE), pour un poste "
                        + "donné ou sans poste. Lecture seule.")
                .inputSchema(McpToolSupport.objectSchema(properties, List.of()))
                .annotations(new ToolAnnotations("Lister les pages",
                        Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    McpCallContext ctx = McpCallContext.require(exchange);
                    if (!ctx.hasScope(McpScopes.PAGES)) {
                        return support.deniedScope(McpScopes.PAGES);
                    }
                    PageSpace space;
                    String rawSpace = McpToolSupport.parseString(request.arguments().get("space"));
                    try {
                        space = rawSpace == null ? PageSpace.FORGE : PageSpace.valueOf(rawSpace.toUpperCase());
                    } catch (IllegalArgumentException ex) {
                        return support.error("space invalide : FORGE ou VIGIE attendus.");
                    }
                    UUID hostId = McpToolSupport.parseUuid(request.arguments().get("host_id"));
                    if (hostId != null && !hostAccess.canAccess(ctx, hostId)) {
                        return support.deniedHost();
                    }
                    List<Map<String, Object>> pages = pageService.list(ctx.user().id(), hostId, space)
                            .stream().map(this::page).toList();
                    Map<String, Object> structured = new LinkedHashMap<>();
                    structured.put("count", pages.size());
                    structured.put("pages", pages);
                    return support.ok(pages.size() + " page(s).", structured);
                })
                .build();
    }

    private Map<String, Object> page(Page page) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", page.getId().toString());
        map.put("title", page.getTitle());
        map.put("description", page.getDescription());
        map.put("space", page.getSpace() == null ? null : page.getSpace().name());
        map.put("host_id", page.getHostId() == null ? null : page.getHostId().toString());
        map.put("current_version", page.getCurrentVersion());
        return map;
    }
}
