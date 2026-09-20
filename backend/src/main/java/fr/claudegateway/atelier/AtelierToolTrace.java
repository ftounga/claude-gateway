package fr.claudegateway.atelier;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.util.StringUtils;

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

    /**
     * Résultat d'outil conservé pour la mémoire — le tour en cours, lui, l'a eu en entier. Relevé de
     * 4 000 à 8 000 (F-119 / SF-119-03) : l'agent perdait ses preuves plus vite que ses affirmations ;
     * une borne plus large garde davantage de sortie citable, la compaction bornant déjà le volume total.
     */
    static final int MAX_RESULT_CHARS = 8_000;
    /**
     * Trajectoire d'un tour : au-delà, les étapes les plus anciennes du tour sont abandonnées. Relevé
     * de 40 000 à 60 000 (F-119 / SF-119-03), de pair avec la fenêtre de rejeu élargie.
     */
    static final int MAX_TRACE_CHARS = 60_000;
    /**
     * Marqueur de coupe : un résultat tronqué le dit, jamais en silence. <b>Suffixe</b> depuis F-119 /
     * SF-119-03 : on garde désormais la <b>tête</b> du résultat (comme l'affichage en direct), la coupe
     * est donc à la fin.
     */
    static final String TRUNCATION_MARK = "\n… (fin tronquée)";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Une itération de la boucle : le <b>raisonnement</b> du modèle, son commentaire, puis ses
     * appels d'outils — exactement l'ordre dans lequel ils ont été envoyés au fournisseur.
     *
     * <p><b>{@code thoughts} est arrivé avec F-134 / SF-134-04.</b> Une trace écrite avant ne le
     * porte pas : le champ vaut alors {@code null} et le tour se rejoue comme avant, sans erreur —
     * c'est tout l'objet du {@code @JsonIgnoreProperties} ci-dessus.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(String text, List<Call> calls, List<Thought> thoughts) {

        /** Forme d'avant F-134 / SF-134-04, conservée pour les appelants et les traces déjà écrites. */
        public Step(String text, List<Call> calls) {
            this(text, calls, List.of());
        }

        public Step {
            thoughts = thoughts == null ? List.of() : List.copyOf(thoughts);
        }
    }

    /**
     * Un bloc de <b>raisonnement</b> du modèle, conservé pour être rejoué <b>tel quel</b>
     * (F-134 / SF-134-04).
     *
     * <p><b>Pourquoi il est persisté.</b> Pendant un tour, le message assistant envoyé au
     * fournisseur commence par ses blocs de raisonnement — et c'est cet ensemble qu'il met en
     * cache. Jusqu'ici, le rejeu les omettait : le ruban renvoyé différait donc de celui qui était
     * en mémoire <b>dès le premier bloc de chaque tour</b>, et tout ce qui suivait était réécrit au
     * double du tarif d'entrée. Mesuré : 23 % de contexte relu sur un fil de deux tours.</p>
     *
     * <p><b>Le bloc est signé.</b> Le fournisseur exige de le retrouver inchangé : ni retouché, ni
     * reconstruit, ni omis (voir {@code AgentContentBlock.Reasoning}, F-39 / SF-39-10). Le texte est
     * le plus souvent vide — la signature, elle, ne l'est jamais et doit survivre au trajet.</p>
     *
     * @param text      texte du raisonnement, souvent vide
     * @param signature signature opaque du fournisseur
     * @param redacted  charge d'un raisonnement <b>expurgé</b>, réémise sans interprétation ; quand
     *                  elle est présente, elle prime et les deux autres champs sont ignorés
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Thought(String text, String signature, String redacted) {

        /** Vrai quand le bloc ne porte rien : ni signature, ni charge expurgée. Inutile à rejouer. */
        boolean isEmpty() {
            return !StringUtils.hasText(signature) && !StringUtils.hasText(redacted);
        }
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
     * Résultat d'outil ramené à la taille de la mémoire. La <b>tête</b> est conservée (F-119 /
     * SF-119-03), <b>le même extrait que l'affichage en direct</b> (bashOutcome et readOutcome gardent
     * le début) : sans quoi la mémoire de l'agent d'un même résultat bascule d'un tour à l'autre —
     * début en direct, fin au rejeu — et il se contredit. Le compromis assumé : sur une sortie
     * très longue, le code de sortie (en queue) peut sortir de la mémoire ; le signal d'échec, lui,
     * a été capté en direct (SF-119-01) sur le résultat complet.
     */
    static String boundResult(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= MAX_RESULT_CHARS
                ? content
                : content.substring(0, MAX_RESULT_CHARS) + TRUNCATION_MARK;
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
            // Le raisonnement EN TÊTE, avant le texte : c'est l'ordre dans lequel le fournisseur
            // l'a reçu, et donc mis en cache (F-134 / SF-134-04). Le remettre ailleurs — ou pas du
            // tout — suffit à faire réécrire tout ce qui suit.
            for (Thought thought : step.thoughts()) {
                if (thought == null || thought.isEmpty()) {
                    continue;
                }
                assistant.add(StringUtils.hasText(thought.redacted())
                        ? new AgentContentBlock.RedactedReasoning(thought.redacted())
                        : new AgentContentBlock.Reasoning(
                                thought.text() == null ? "" : thought.text(), thought.signature()));
            }
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
