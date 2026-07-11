package fr.claudegateway.agent;

import java.util.List;

/**
 * Message d'une conversation d'agent (F-28), porteur de blocs de contenu (texte, tool_use,
 * tool_result). Neutre vis-à-vis du fournisseur.
 *
 * @param role    {@code user} ou {@code assistant}
 * @param content blocs ordonnés du message
 */
public record AgentMessage(String role, List<AgentContentBlock> content) {

    /** Message utilisateur texte. */
    public static AgentMessage userText(String text) {
        return new AgentMessage("user", List.of(new AgentContentBlock.Text(text)));
    }

    /** Message assistant (texte et/ou appels d'outils). */
    public static AgentMessage assistant(List<AgentContentBlock> content) {
        return new AgentMessage("assistant", content);
    }

    /** Message utilisateur porteur de résultats d'outils. */
    public static AgentMessage toolResults(List<AgentContentBlock> results) {
        return new AgentMessage("user", results);
    }
}
