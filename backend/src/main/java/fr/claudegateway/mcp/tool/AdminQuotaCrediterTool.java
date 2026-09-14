package fr.claudegateway.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import fr.claudegateway.mcp.McpAdminGuard;
import fr.claudegateway.mcp.McpCallContext;
import fr.claudegateway.mcp.McpToolProvider;
import fr.claudegateway.mcp.McpToolSupport;
import fr.claudegateway.quota.QuotaService;

/**
 * Outil MCP {@code admin_quota_crediter} (F-112 / SF-112-07) : crédite des jetons bonus au quota d'un
 * utilisateur. Relaie {@link QuotaService#creditBonusTokens}. Double garde : périmètre {@code admin}
 * <b>et</b> rôle ADMIN.
 */
@Component
public class AdminQuotaCrediterTool implements McpToolProvider {

    static final String NAME = "admin_quota_crediter";

    private final QuotaService quotaService;
    private final McpAdminGuard adminGuard;
    private final McpToolSupport support;

    public AdminQuotaCrediterTool(QuotaService quotaService, McpAdminGuard adminGuard,
            McpToolSupport support) {
        this.quotaService = quotaService;
        this.adminGuard = adminGuard;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("user_id", McpToolSupport.property("string",
                "Identifiant (UUID) de l'utilisateur à créditer."));
        properties.put("tokens", McpToolSupport.property("integer",
                "Nombre de jetons bonus à créditer (> 0)."));
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Administration : créditer un quota")
                .description("Crédite des jetons bonus au quota d'un utilisateur. Réservé au rôle "
                        + "ADMIN. Modifie le quota.")
                .inputSchema(McpToolSupport.objectSchema(properties, List.of("user_id", "tokens")))
                .annotations(new ToolAnnotations("Administration : créditer un quota",
                        Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    McpCallContext ctx = McpCallContext.require(exchange);
                    CallToolResult denied = adminGuard.check(ctx);
                    if (denied != null) {
                        return denied;
                    }
                    UUID targetUserId = McpToolSupport.parseUuid(request.arguments().get("user_id"));
                    if (targetUserId == null) {
                        return support.error("user_id manquant ou invalide (UUID attendu).");
                    }
                    long tokens = parseTokens(request.arguments().get("tokens"));
                    if (tokens <= 0) {
                        return support.error("tokens doit être un entier strictement positif.");
                    }
                    try {
                        quotaService.creditBonusTokens(targetUserId, tokens);
                    } catch (RuntimeException ex) {
                        return support.error("Crédit impossible : " + ex.getMessage());
                    }
                    Map<String, Object> structured = new LinkedHashMap<>();
                    structured.put("user_id", targetUserId.toString());
                    structured.put("tokens_credited", tokens);
                    return support.ok("Quota crédité.", structured);
                })
                .build();
    }

    private long parseTokens(Object raw) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        String value = McpToolSupport.parseString(raw);
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
