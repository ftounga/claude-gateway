package fr.claudegateway.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import fr.claudegateway.access.AccessCodeService;
import fr.claudegateway.access.AccessCodeService.IssuedAccessCode;
import fr.claudegateway.mcp.McpAdminGuard;
import fr.claudegateway.mcp.McpCallContext;
import fr.claudegateway.mcp.McpToolProvider;
import fr.claudegateway.mcp.McpToolSupport;

/**
 * Outil MCP {@code admin_codes_acces_emettre} (F-112 / SF-112-07) : émet un code d'accès. Relaie
 * {@link AccessCodeService#issue}. Double garde : périmètre {@code admin} <b>et</b> rôle ADMIN. Le
 * code émis est l'objet même de l'outil (comme l'écran d'administration) et est rendu une fois.
 */
@Component
public class AdminCodesAccesEmettreTool implements McpToolProvider {

    static final String NAME = "admin_codes_acces_emettre";

    private final AccessCodeService accessCodeService;
    private final McpAdminGuard adminGuard;
    private final McpToolSupport support;

    public AdminCodesAccesEmettreTool(AccessCodeService accessCodeService, McpAdminGuard adminGuard,
            McpToolSupport support) {
        this.accessCodeService = accessCodeService;
        this.adminGuard = adminGuard;
        this.support = support;
    }

    @Override
    public SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("label", McpToolSupport.property("string", "Libellé du code (à quoi il sert)."));
        properties.put("email", McpToolSupport.property("string",
                "Adresse à qui le code est assigné."));
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Administration : émettre un code d'accès")
                .description("Émet un code d'accès assigné à une adresse. Réservé au rôle ADMIN. Le "
                        + "code n'est montré qu'une fois.")
                .inputSchema(McpToolSupport.objectSchema(properties, List.of("label", "email")))
                .annotations(new ToolAnnotations("Administration : émettre un code d'accès",
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
                    String label = McpToolSupport.parseString(request.arguments().get("label"));
                    String email = McpToolSupport.parseString(request.arguments().get("email"));
                    if (label == null || email == null) {
                        return support.error("label et email sont requis.");
                    }
                    try {
                        String finalLabel = label;
                        String finalEmail = email;
                        IssuedAccessCode issued = support.withPrincipal(ctx.user(),
                                () -> accessCodeService.issue(finalLabel, finalEmail));
                        Map<String, Object> structured = new LinkedHashMap<>();
                        structured.put("code", issued.code());
                        structured.put("detail", support.toJsonTree(issued.view()));
                        return support.ok("Code d'accès émis (montré une seule fois).", structured);
                    } catch (RuntimeException ex) {
                        return support.error("Émission refusée : " + ex.getMessage());
                    }
                })
                .build();
    }
}
