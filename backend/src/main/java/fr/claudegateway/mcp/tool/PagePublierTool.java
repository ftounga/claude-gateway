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
import fr.claudegateway.pages.PagePlace;
import fr.claudegateway.pages.PageService;
import fr.claudegateway.pages.PageService.PublishedPage;
import fr.claudegateway.pages.PageSpace;

/**
 * Outil MCP {@code page_publier} (F-112 / SF-112-07) : publie une page HTML. Relaie
 * {@link PageService#publish}. Périmètre {@code pages}, accès poste par poste si un poste est visé.
 */
@Component
public class PagePublierTool implements McpToolProvider {

    static final String NAME = "page_publier";

    private final PageService pageService;
    private final McpHostAccess hostAccess;
    private final McpToolSupport support;

    public PagePublierTool(PageService pageService, McpHostAccess hostAccess, McpToolSupport support) {
        this.pageService = pageService;
        this.hostAccess = hostAccess;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", McpToolSupport.property("string", "Titre court et distinctif de la page."));
        properties.put("html", McpToolSupport.property("string", "Le document HTML (UTF-8)."));
        properties.put("description", McpToolSupport.property("string", "Phrase de description (facultative)."));
        properties.put("space", McpToolSupport.property("string", "Espace : FORGE (défaut) ou VIGIE."));
        properties.put("page_id", McpToolSupport.property("string",
                "Page à republier (UUID), ou vide pour une nouvelle page."));
        properties.put("host_id", McpToolSupport.property("string",
                "Poste de rattachement (UUID), facultatif."));
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Publier une page")
                .description("Publie une page HTML dans un espace (FORGE ou VIGIE). Rend son "
                        + "identifiant et sa version. Modifie l'état (nouvelle version).")
                .inputSchema(McpToolSupport.objectSchema(properties, List.of("title", "html")))
                .annotations(new ToolAnnotations("Publier une page",
                        Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    McpCallContext ctx = McpCallContext.require(exchange);
                    if (!ctx.hasScope(McpScopes.PAGES)) {
                        return support.deniedScope(McpScopes.PAGES);
                    }
                    String title = McpToolSupport.parseString(request.arguments().get("title"));
                    String html = McpToolSupport.parseString(request.arguments().get("html"));
                    if (title == null || html == null) {
                        return support.error("title et html sont requis.");
                    }
                    PageSpace space = parseSpace(request.arguments().get("space"));
                    if (space == null) {
                        return support.error("space invalide : FORGE ou VIGIE attendus.");
                    }
                    UUID hostId = McpToolSupport.parseUuid(request.arguments().get("host_id"));
                    if (hostId != null && !hostAccess.canAccess(ctx, hostId)) {
                        return support.deniedHost();
                    }
                    UUID pageId = McpToolSupport.parseUuid(request.arguments().get("page_id"));
                    String description = McpToolSupport.parseString(request.arguments().get("description"));
                    PagePlace place = new PagePlace(ctx.user().id(), space, hostId, null);
                    try {
                        PublishedPage published =
                                pageService.publish(place, pageId, title, description, html, Map.of());
                        Map<String, Object> structured = new LinkedHashMap<>();
                        structured.put("page_id", published.page().getId().toString());
                        structured.put("title", published.page().getTitle());
                        structured.put("version", published.page().getCurrentVersion());
                        structured.put("space", published.page().getSpace().name());
                        return support.ok("Page publiée.", structured);
                    } catch (RuntimeException ex) {
                        return support.error("Publication refusée : " + ex.getMessage());
                    }
                })
                .build();
    }

    private PageSpace parseSpace(Object raw) {
        String value = McpToolSupport.parseString(raw);
        if (value == null) {
            return PageSpace.FORGE;
        }
        try {
            return PageSpace.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
