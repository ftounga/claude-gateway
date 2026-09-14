package fr.claudegateway.mcp.tool;

import java.util.List;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;

import fr.claudegateway.mcp.McpResourceProperties;
import fr.claudegateway.mcp.McpResourceProvider;

/**
 * Ressource MCP {@code cg://guide} (F-112 / SF-112-08) : le guide « Connecter une IA », en Markdown,
 * lisible par référence depuis n'importe quel client. Il donne l'adresse du serveur et le pas-à-pas.
 * Contenu statique de la gateway — aucune donnée d'un tiers.
 */
@Component
public class GuideResource implements McpResourceProvider {

    static final String URI = "cg://guide";

    private final McpResourceProperties resourceProperties;

    public GuideResource(McpResourceProperties resourceProperties) {
        this.resourceProperties = resourceProperties;
    }

    @Override
    public SyncResourceSpecification specification() {
        Resource resource = Resource.builder()
                .uri(URI)
                .name("Guide : connecter une IA")
                .description("Comment connecter une IA (Claude Code, Claude Desktop, claude.ai, "
                        + "Codex) au serveur MCP de claude-gateway, et ce qu'elle peut y faire.")
                .mimeType("text/markdown")
                .build();

        return new SyncResourceSpecification(resource, (exchange, request) ->
                new ReadResourceResult(List.of(
                        new TextResourceContents(URI, "text/markdown", guide()))));
    }

    private String guide() {
        String url = resourceProperties.resource();
        return """
                # Connecter une IA à claude-gateway

                Adresse du serveur MCP : **%s**

                L'IA se connecte avec **votre compte** (OAuth 2.1 dans le navigateur) ou un **jeton
                personnel** créé dans Réglages → « IA connectées ». Elle agit alors avec **vos droits**
                et votre cloisonnement.

                ## Claude Code
                ```
                claude mcp add --transport http claude-gateway %s
                ```

                ## Claude Desktop / claude.ai
                Réglages → Connecteurs → « Ajouter un connecteur personnalisé » → collez l'adresse
                ci-dessus, puis connectez-vous et consentez dans le navigateur.

                ## Codex
                Ajoutez un serveur MCP HTTP pointant sur l'adresse ci-dessus dans votre configuration.

                ## Ce que l'IA peut faire
                - Postes et Forge : lister/détailler, vérifier, mettre à jour le runner.
                - Terminaux : écrire (lancer un tour), suivre, préciser, interrompre.
                - Vigie et Radar : synchroniser, lire, donner une nouvelle, clore, lier.
                - Pages, courriel, compte, administration (selon vos droits et périmètres accordés).

                ## Ce que l'IA ne peut **jamais** faire
                - **S'autoriser elle-même** : les commandes sur votre machine et les écritures
                  Microsoft 365 restent à valider par **vous**, dans l'application. L'outil
                  `autorisations_en_attente` les liste, sans jamais les accorder.
                """.formatted(url, url);
    }
}
