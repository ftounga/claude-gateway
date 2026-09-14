package fr.claudegateway.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.mail.ClientMailTool;
import fr.claudegateway.mcp.McpCallContext;
import fr.claudegateway.mcp.McpHostAccess;
import fr.claudegateway.mcp.McpScopes;
import fr.claudegateway.mcp.McpToolProvider;
import fr.claudegateway.mcp.McpToolSupport;

/**
 * Outil MCP {@code courriel_m_envoyer} (F-112 / SF-112-07) : envoie un courriel au titulaire du
 * compte — <b>destinataire résolu par la gateway</b> (F-110). Relaie {@link ClientMailTool#send} via
 * le terminal du poste (qui porte l'adresse de réception et les limites F-110). Périmètre
 * {@code courriel}, accès poste par poste. Le corps n'est jamais renvoyé ; les secrets du corps sont
 * refusés par F-110.
 */
@Component
public class CourrielMEnvoyerTool implements McpToolProvider {

    static final String NAME = "courriel_m_envoyer";

    private final ClientMailTool clientMailTool;
    private final WorkspaceService workspaceService;
    private final McpHostAccess hostAccess;
    private final McpToolSupport support;
    private final ObjectMapper objectMapper;

    public CourrielMEnvoyerTool(ClientMailTool clientMailTool, WorkspaceService workspaceService,
            McpHostAccess hostAccess, McpToolSupport support, ObjectMapper objectMapper) {
        this.clientMailTool = clientMailTool;
        this.workspaceService = workspaceService;
        this.hostAccess = hostAccess;
        this.support = support;
        this.objectMapper = objectMapper;
    }

    @Override
    public SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("host_id", McpToolSupport.property("string",
                "Poste (UUID) dont l'adresse de réception recevra le courriel."));
        properties.put("subject", McpToolSupport.property("string", "Objet (une ligne)."));
        properties.put("body", McpToolSupport.property("string", "Corps en Markdown."));
        Tool tool = Tool.builder()
                .name(NAME)
                .title("M'envoyer un courriel")
                .description("Envoie un courriel au titulaire du compte ; le destinataire est résolu "
                        + "par la gateway (F-110). Le corps n'est pas renvoyé ; un secret dans le "
                        + "corps fait refuser l'envoi.")
                .inputSchema(McpToolSupport.objectSchema(properties, List.of("host_id", "subject", "body")))
                .annotations(new ToolAnnotations("M'envoyer un courriel",
                        Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE))
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    McpCallContext ctx = McpCallContext.require(exchange);
                    if (!ctx.hasScope(McpScopes.COURRIEL)) {
                        return support.deniedScope(McpScopes.COURRIEL);
                    }
                    UUID hostId = McpToolSupport.parseUuid(request.arguments().get("host_id"));
                    if (hostId == null) {
                        return support.error("host_id manquant ou invalide (UUID attendu).");
                    }
                    if (!hostAccess.canAccess(ctx, hostId)) {
                        return support.deniedHost();
                    }
                    String subject = McpToolSupport.parseString(request.arguments().get("subject"));
                    String body = McpToolSupport.parseString(request.arguments().get("body"));
                    if (subject == null || body == null) {
                        return support.error("subject et body sont requis.");
                    }
                    Workspace terminal;
                    try {
                        terminal = workspaceService.openHostTerminal(ctx.user().id(), hostId);
                    } catch (RuntimeException ex) {
                        return support.error("Poste introuvable ou inaccessible.");
                    }
                    ObjectNode input = objectMapper.createObjectNode();
                    input.put("subject", subject);
                    input.put("body", body);
                    ClientMailTool.Outcome outcome = clientMailTool.send(ctx.user().id(), terminal, input);
                    if (outcome.error()) {
                        return support.error(outcome.content());
                    }
                    Map<String, Object> structured = new LinkedHashMap<>();
                    structured.put("sent", true);
                    structured.put("receipt", support.toJsonTree(outcome.receipt()));
                    return support.ok("Courriel envoyé.", structured);
                })
                .build();
    }
}
