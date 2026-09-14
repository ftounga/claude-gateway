package fr.claudegateway.mcp.tool;

import java.util.Map;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import fr.claudegateway.billing.SubscriptionService;
import fr.claudegateway.mcp.McpCallContext;
import fr.claudegateway.mcp.McpScopes;
import fr.claudegateway.mcp.McpToolProvider;
import fr.claudegateway.mcp.McpToolSupport;

/**
 * Outil MCP {@code compte_abonnement} (F-112 / SF-112-07) : l'abonnement du compte connecté. Relaie
 * {@link SubscriptionService#getOrCreateForUser}. Périmètre {@code compte:lire}, isolation {@code user_id}.
 */
@Component
public class CompteAbonnementTool implements McpToolProvider {

    static final String NAME = "compte_abonnement";

    private final SubscriptionService subscriptionService;
    private final McpToolSupport support;

    public CompteAbonnementTool(SubscriptionService subscriptionService, McpToolSupport support) {
        this.subscriptionService = subscriptionService;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Mon abonnement")
                .description("L'abonnement du compte connecté (plan, période, état). Lecture seule.")
                .inputSchema(McpToolSupport.emptySchema())
                .annotations(new ToolAnnotations("Mon abonnement",
                        Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    McpCallContext ctx = McpCallContext.require(exchange);
                    if (!ctx.hasScope(McpScopes.COMPTE_LIRE)) {
                        return support.deniedScope(McpScopes.COMPTE_LIRE);
                    }
                    Object subscription = support.toJsonTree(
                            subscriptionService.getOrCreateForUser(ctx.user().id()));
                    return support.ok("Abonnement du compte.", Map.of("abonnement", subscription));
                })
                .build();
    }
}
