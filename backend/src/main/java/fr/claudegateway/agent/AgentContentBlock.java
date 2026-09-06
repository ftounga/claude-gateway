package fr.claudegateway.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Bloc de contenu d'un message d'agent (F-28 / Atelier). Structure neutre vis-à-vis du fournisseur :
 * texte, raisonnement, appel d'outil ({@code tool_use}) ou résultat d'outil ({@code tool_result}).
 * Le mapping vers l'API Anthropic est confiné à {@code AnthropicAgentProvider} (Provider
 * Independence).
 */
public sealed interface AgentContentBlock
        permits AgentContentBlock.Text, AgentContentBlock.ToolUse, AgentContentBlock.ToolResult,
        AgentContentBlock.Reasoning, AgentContentBlock.RedactedReasoning {

    /** Texte simple (message utilisateur ou assistant). */
    record Text(String text) implements AgentContentBlock {
    }

    /** Demande d'appel d'outil émise par l'assistant. */
    record ToolUse(String id, String name, JsonNode input) implements AgentContentBlock {
    }

    /** Résultat d'un outil, renvoyé à l'assistant (référence l'{@code id} du {@link ToolUse}). */
    record ToolResult(String toolUseId, String content, boolean isError) implements AgentContentBlock {
    }

    /**
     * Raisonnement rendu par l'assistant avant ses appels d'outils (F-39 / SF-39-10).
     *
     * <p><b>Rejoué tel quel, jamais reconstruit.</b> Le bloc est <b>signé</b> par le fournisseur, qui
     * exige de le retrouver inchangé sur le dernier tour d'assistant quand on lui renvoie les
     * {@code tool_result} : le modifier ou l'omettre casse la boucle. Le texte peut être <b>vide</b>
     * — c'est le cas par défaut, le contenu du raisonnement n'étant pas rendu (décision D-L5-5) —
     * mais la signature, elle, doit survivre au trajet.</p>
     *
     * @param text      texte du raisonnement, souvent vide
     * @param signature signature opaque du fournisseur, ou {@code null} si absente
     */
    record Reasoning(String text, String signature) implements AgentContentBlock {
    }

    /**
     * Raisonnement <b>expurgé</b> par le fournisseur (F-39 / SF-39-10) : son contenu est chiffré,
     * mais il occupe une place dans la séquence et doit être renvoyé tel quel, au même titre qu'un
     * {@link Reasoning}. Le supprimer laisserait un trou dans une suite que le fournisseur vérifie.
     *
     * @param data charge opaque, réémise sans interprétation
     */
    record RedactedReasoning(String data) implements AgentContentBlock {
    }
}
