package fr.claudegateway.agent;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Bloc de contenu d'un message d'agent (F-28 / Atelier). Structure neutre vis-à-vis du fournisseur :
 * texte, raisonnement, appel d'outil ({@code tool_use}), résultat d'outil ({@code tool_result}) ou,
 * depuis F-121 / SF-121-15, un média que le fournisseur « voit » lui-même — une {@link Image} ou un
 * {@link Document}. Le mapping vers l'API Anthropic est confiné à {@code AnthropicAgentProvider}
 * (Provider Independence).
 */
public sealed interface AgentContentBlock
        permits AgentContentBlock.Text, AgentContentBlock.ToolUse, AgentContentBlock.ToolResult,
        AgentContentBlock.Reasoning, AgentContentBlock.RedactedReasoning, AgentContentBlock.Image,
        AgentContentBlock.Document {

    /** Texte simple (message utilisateur ou assistant). */
    record Text(String text) implements AgentContentBlock {
    }

    /** Demande d'appel d'outil émise par l'assistant. */
    record ToolUse(String id, String name, JsonNode input) implements AgentContentBlock {
    }

    /**
     * Résultat d'un outil, renvoyé à l'assistant (référence l'{@code id} du {@link ToolUse}).
     *
     * <p><b>Multimodal</b> (F-121 / SF-121-15) : {@code blocks} porte, quand il n'est pas vide, des
     * sous-blocs riches — typiquement une {@link Image} ou un {@link Document} suivis d'un
     * {@link Text} de légende. Le fournisseur reçoit alors un {@code content} <b>tableau</b> plutôt
     * qu'une chaîne. La forme historique (texte seul, {@code blocks} nul) reste inchangée : aucun
     * appel existant n'a à changer.</p>
     *
     * @param content légende/texte du résultat ; seule charge quand {@code blocks} est nul ou vide
     * @param blocks  sous-blocs riches du résultat, ou {@code null} pour un résultat texte pur
     */
    record ToolResult(String toolUseId, String content, boolean isError, List<AgentContentBlock> blocks)
            implements AgentContentBlock {

        /** Résultat texte pur : forme historique, sans sous-blocs riches. */
        public ToolResult(String toolUseId, String content, boolean isError) {
            this(toolUseId, content, isError, null);
        }
    }

    /**
     * Image que le fournisseur « voit » nativement (F-121 / SF-121-15), transportée en Base64. Neutre
     * vis-à-vis du fournisseur : le mapping vers le bloc {@code image} de l'API est confiné à
     * {@code AnthropicAgentProvider}. Apparaît principalement dans les sous-blocs d'un
     * {@link ToolResult} de {@code read_file}.
     *
     * @param mediaType type MIME (ex. {@code image/png})
     * @param base64Data octets de l'image encodés en Base64 standard, jamais tronqués
     */
    record Image(String mediaType, String base64Data) implements AgentContentBlock {
    }

    /**
     * Document que le fournisseur « voit » nativement (F-121 / SF-121-15) — typiquement un PDF —,
     * transporté en Base64. Neutre vis-à-vis du fournisseur ; mapping confiné à
     * {@code AnthropicAgentProvider} (bloc {@code document}).
     *
     * @param mediaType type MIME (ex. {@code application/pdf})
     * @param base64Data octets du document encodés en Base64 standard, jamais tronqués
     */
    record Document(String mediaType, String base64Data) implements AgentContentBlock {
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
