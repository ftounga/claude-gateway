package fr.claudegateway.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Bloc de contenu d'un message d'agent (F-28 / Atelier). Structure neutre vis-à-vis du fournisseur :
 * texte, appel d'outil ({@code tool_use}) ou résultat d'outil ({@code tool_result}). Le mapping vers
 * l'API Anthropic est confiné à {@code AnthropicAgentProvider} (Provider Independence).
 */
public sealed interface AgentContentBlock
        permits AgentContentBlock.Text, AgentContentBlock.ToolUse, AgentContentBlock.ToolResult {

    /** Texte simple (message utilisateur ou assistant). */
    record Text(String text) implements AgentContentBlock {
    }

    /** Demande d'appel d'outil émise par l'assistant. */
    record ToolUse(String id, String name, JsonNode input) implements AgentContentBlock {
    }

    /** Résultat d'un outil, renvoyé à l'assistant (référence l'{@code id} du {@link ToolUse}). */
    record ToolResult(String toolUseId, String content, boolean isError) implements AgentContentBlock {
    }
}
