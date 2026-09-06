package fr.claudegateway.atelier;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;

/**
 * Trajectoire d'outils d'un tour de la boucle maison (F-39 / SF-39-03) : ce que l'agent a appelé,
 * avec quels arguments, et ce que ça a répondu.
 *
 * <p>Sans elle, l'historique rejoué au fournisseur est du <b>texte seul</b> : au message suivant,
 * l'agent relit les fichiers qu'il vient de lire et relance les commandes dont il a déjà la sortie.
 * On paie deux fois le même travail, et l'utilisateur attend deux fois.</p>
 *
 * <p>Structure neutre vis-à-vis du fournisseur — elle se reconstruit en {@link AgentContentBlock},
 * jamais en JSON d'API. Donnée de <b>rejeu</b> : persistée en document sur le message, lue en bloc
 * avec l'historique, jamais requêtée.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AtelierToolTrace(List<Step> steps) {

    /** Résultat d'outil conservé pour la mémoire — le tour en cours, lui, l'a eu en entier. */
    static final int MAX_RESULT_CHARS = 4_000;
    /** Trajectoire d'un tour : au-delà, les étapes les plus anciennes du tour sont abandonnées. */
    static final int MAX_TRACE_CHARS = 40_000;
    /** Marqueur de coupe : un résultat tronqué le dit, jamais en silence. */
    static final String TRUNCATION_MARK = "… (début tronqué)\n";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Une itération de la boucle : le commentaire de l'agent, puis ses appels d'outils. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(String text, List<Call> calls) {
    }

    /** Un appel d'outil et son résultat, appariés par {@code id} — l'API refuse l'un sans l'autre. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Call(String id, String name, JsonNode input, String result, boolean error) {
    }

    public static AtelierToolTrace empty() {
        return new AtelierToolTrace(List.of());
    }

    public boolean isEmpty() {
        return steps == null || steps.isEmpty();
    }

    /**
     * Résultat d'outil ramené à la taille de la mémoire. La <b>fin</b> est conservée : c'est là que
     * se trouvent le code de sortie et le message d'erreur. Garder le début reviendrait à mémoriser
     * la question sans la réponse.
     */
    static String boundResult(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= MAX_RESULT_CHARS
                ? content
                : TRUNCATION_MARK + content.substring(content.length() - MAX_RESULT_CHARS);
    }

    /**
     * Sérialise la trajectoire, en abandonnant ses étapes les plus <b>anciennes</b> tant que le
     * document dépasse {@link #MAX_TRACE_CHARS}.
     *
     * @return le document JSON, ou {@code null} s'il n'y a rien à retenir ou si la sérialisation
     *         échoue — un défaut de mémoire ne doit jamais faire perdre la réponse de l'agent
     */
    public String toJson() {
        if (isEmpty()) {
            return null;
        }
        List<Step> kept = new ArrayList<>(steps);
        while (!kept.isEmpty()) {
            try {
                String json = MAPPER.writeValueAsString(new AtelierToolTrace(List.copyOf(kept)));
                if (json.length() <= MAX_TRACE_CHARS) {
                    return json;
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                return null;
            }
            kept.remove(0);
        }
        return null;
    }

    /**
     * Relit une trajectoire stockée. Un document illisible (ancien, tronqué) rend une trajectoire
     * <b>vide</b> plutôt que de casser la conversation : le message est alors rejoué en texte seul,
     * exactement comme avant SF-39-03.
     */
    public static AtelierToolTrace fromJson(String json) {
        if (json == null || json.isBlank()) {
            return empty();
        }
        try {
            AtelierToolTrace trace = MAPPER.readValue(json, AtelierToolTrace.class);
            return trace == null || trace.steps() == null ? empty() : trace;
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            return empty();
        }
    }

    /**
     * Reconstruit les messages d'agent de la trajectoire : pour chaque itération, un message
     * assistant portant ses {@code tool_use}, puis un message portant les {@code tool_result}
     * appariés. Une itération sans appel exploitable est ignorée — un {@code tool_use} sans
     * {@code tool_result} ferait refuser tout le tour par le fournisseur.
     */
    public List<AgentMessage> replay() {
        List<AgentMessage> messages = new ArrayList<>();
        if (isEmpty()) {
            return messages;
        }
        for (Step step : steps) {
            List<Call> calls = step.calls() == null ? List.of() : step.calls();
            List<AgentContentBlock> assistant = new ArrayList<>();
            List<AgentContentBlock> results = new ArrayList<>();
            if (step.text() != null && !step.text().isBlank()) {
                assistant.add(new AgentContentBlock.Text(step.text()));
            }
            for (Call call : calls) {
                if (call == null || call.id() == null || call.id().isBlank()
                        || call.name() == null || call.name().isBlank()) {
                    continue;
                }
                assistant.add(new AgentContentBlock.ToolUse(call.id(), call.name(), call.input()));
                String content = call.result() == null || call.result().isBlank() ? "(vide)" : call.result();
                results.add(new AgentContentBlock.ToolResult(call.id(), content, call.error()));
            }
            if (results.isEmpty()) {
                continue; // Rien d'apparié : cette itération n'a rien à apprendre au fournisseur.
            }
            messages.add(AgentMessage.assistant(assistant));
            messages.add(AgentMessage.toolResults(results));
        }
        return messages;
    }
}
