package fr.claudegateway.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import fr.claudegateway.auth.AuthenticatedUser;

/**
 * Outil MCP {@code session_info} (F-112 / SF-112-01) : renvoie l'identité <b>du jeton présenté</b>
 * (identifiant, courriel, rôle) et l'adresse du serveur. C'est l'outil de fondation : il ne touche
 * à aucune donnée métier, mais prouve la chaîne complète auth → contexte tenant → outil, et sert de
 * vérification de connexion pour les clients IA.
 *
 * <p>Lecture pure ({@code readOnlyHint}). L'identité est lue depuis {@link McpCallContext}, jamais
 * d'un paramètre : deux jetons distincts donnent deux identités distinctes (isolation {@code user_id}).</p>
 */
@Component
public class SessionInfoTool implements McpToolProvider {

    static final String NAME = "session_info";

    @Override
    public SyncToolSpecification specification() {
        Tool tool = Tool.builder()
                .name(NAME)
                .title("Ma session")
                .description("Renvoie l'identité du compte connecté (identifiant, courriel, rôle) et "
                        + "confirme que la connexion au serveur MCP fonctionne. Lecture seule, sans effet.")
                .inputSchema(new JsonSchema("object", Map.of(), List.of(), Boolean.FALSE, null, null))
                .annotations(new ToolAnnotations(
                        "Ma session",
                        Boolean.TRUE,   // readOnlyHint
                        Boolean.FALSE,  // destructiveHint
                        Boolean.TRUE,   // idempotentHint
                        Boolean.FALSE,  // openWorldHint
                        Boolean.FALSE)) // returnDirect
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    AuthenticatedUser user = McpCallContext.require(exchange).user();
                    Map<String, Object> structured = new LinkedHashMap<>();
                    structured.put("user_id", user.id().toString());
                    structured.put("email", user.email());
                    structured.put("role", user.role().name());
                    structured.put("server", "claude-gateway");
                    String text = "Connecté en tant que " + user.email()
                            + " (rôle " + user.role().name() + ").";
                    return CallToolResult.builder()
                            .addTextContent(text)
                            .structuredContent(structured)
                            .build();
                })
                .build();
    }
}
