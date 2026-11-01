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
    public record Step(String text, List<Call> calls, List<Thought> thoughts, boolean serverTools) {

        /** Forme d'avant F-134 / SF-134-04, conservée pour les appelants et les traces déjà écrites. */
        public Step(String text, List<Call> calls) {
            this(text, calls, List.of(), false);
        }

        /** Forme d'avant F-117 / SF-117-11 : une étape sans outil serveur. */
        public Step(String text, List<Call> calls, List<Thought> thoughts) {
            this(text, calls, thoughts, false);
        }

        public Step {
            thoughts = thoughts == null ? List.of() : List.copyOf(thoughts);
        }

        /**
         * Vrai si les raisonnements de l'étape peuvent être rejoués <b>tels quels</b> (F-117 /
         * SF-117-11). Ce n'est plus le cas quand la réponse les entrelaçait avec des blocs d'outils
         * serveur ({@code web_search}, {@code web_fetch}) : la trace ne garde pas ces blocs, le message
         * rejoué n'est donc plus celui d'origine, et le fournisseur le refuse en {@code 400} — « thinking
         * blocks in the latest assistant message cannot be modified » — à <b>chaque</b> envoi suivant.
         * Une trace écrite avant ce correctif ne porte pas {@code serverTools} : plusieurs raisonnements
         * dans une même réponse trahissent cet entrelacement, seule façon d'en obtenir plus d'un.
         */
        boolean reasoningReplayable() {
            return !serverTools && thoughts.size() <= 1;
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
        return store(false).json();
    }

    /**
     * Ce qui est rangé en base, et ce que le rangement a retiré (F-229 / SF-229-01).
     *
     * @param json         le document, exactement celui de {@link #toJson()} quand rien n'est à signaler
     * @param droppedSteps étapes les plus anciennes abandonnées au plafond {@link #MAX_TRACE_CHARS}
     * @param droppedChars caractères envoyés pendant le tour que portaient ces étapes (raisonnements,
     *                     textes, arguments, résultats déjà bornés) — le surplus des résultats coupés à
     *                     {@link #MAX_RESULT_CHARS} est compté à part, là où le résultat entier est connu
     */
    public record Stored(String json, int droppedSteps, long droppedChars) {
    }

    /**
     * Range la trajectoire comme {@link #toJson()} — <b>même sélection d'étapes, même document</b> — et
     * dit ce que le rangement a retiré (F-229 / SF-229-01). Deux faits <b>sans contenu</b> sont ajoutés
     * au document seulement s'ils sont vrais : {@code droppedSteps} (étapes abandonnées) et
     * {@code contextEdited} (le fournisseur a écarté des résultats d'outils pendant le tour). Le rejeu
     * les ignore ({@link #replay()} ne lit que les étapes) : la requête du tour suivant est inchangée à
     * l'octet ; la mesure du tour suivant, elle, y lit pourquoi son ruban diverge.
     *
     * @param contextEdited vrai si le fournisseur a écarté des résultats d'outils pendant le tour
     */
    public Stored store(boolean contextEdited) {
        if (isEmpty()) {
            return new Stored(null, 0, 0L);
        }
        List<Step> kept = new ArrayList<>(steps);
        int dropped = 0;
        long droppedChars = 0L;
        while (!kept.isEmpty()) {
            try {
                String json = MAPPER.writeValueAsString(new AtelierToolTrace(List.copyOf(kept)));
                if (json.length() <= MAX_TRACE_CHARS) {
                    return new Stored(withFacts(json, dropped, contextEdited), dropped, droppedChars);
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                return new Stored(null, dropped, droppedChars);
            }
            droppedChars += sentChars(kept.remove(0));
            dropped++;
        }
        return new Stored(null, dropped, droppedChars);
    }

    /** Le document, augmenté des faits du rangement — inchangé s'il n'y a rien à dire. */
    private static String withFacts(String json, int dropped, boolean contextEdited) {
        if (dropped == 0 && !contextEdited) {
            return json;
        }
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node =
                    (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(json);
            if (dropped > 0) {
                node.put(DROPPED_STEPS_FIELD, dropped);
            }
            if (contextEdited) {
                node.put(CONTEXT_EDITED_FIELD, true);
            }
            return MAPPER.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | ClassCastException ex) {
            return json; // Un fait de mesure perdu ne coûte jamais la mémoire du tour.
        }
    }

    /** Caractères qu'une étape portait dans la requête : raisonnements, texte, arguments et résultats. */
    private static long sentChars(Step step) {
        long chars = step.text() == null ? 0L : step.text().length();
        for (Thought thought : step.thoughts()) {
            if (thought != null) {
                chars += length(thought.text()) + length(thought.signature()) + length(thought.redacted());
            }
        }
        for (Call call : step.calls() == null ? List.<Call>of() : step.calls()) {
            if (call != null) {
                chars += (call.input() == null ? 0 : call.input().toString().length()) + length(call.result());
            }
        }
        return chars;
    }

    private static long length(String value) {
        return value == null ? 0L : value.length();
    }

    /** Champ du document : étapes abandonnées au rangement (F-229 / SF-229-01). */
    static final String DROPPED_STEPS_FIELD = "droppedSteps";
    /** Champ du document : résultats d'outils écartés par le fournisseur pendant le tour (F-229 / SF-229-01). */
    static final String CONTEXT_EDITED_FIELD = "contextEdited";

    /**
     * Surplus retiré d'un résultat par {@link #boundResult(String)} (F-229 / SF-229-01) : ce que le tour
     * a envoyé et que le rejeu ne renverra pas. Zéro pour un résultat qui tient dans la borne.
     */
    static long cutChars(String content) {
        return content == null ? 0L : Math.max(0L, content.length() - (long) MAX_RESULT_CHARS);
    }

    /** Vrai si ce résultat rejoué a été coupé au stockage : il finit par le marqueur de coupe. */
    public static boolean isTruncatedResult(String content) {
        return content != null && content.endsWith(TRUNCATION_MARK);
    }

    /**
     * Ce qu'un document rangé dit de son tour (F-229 / SF-229-01) : des faits, jamais de contenu.
     *
     * @param stepsDropped     des étapes ont été abandonnées au plafond {@link #MAX_TRACE_CHARS}
     * @param reasoningDropped un raisonnement présent pendant le tour n'est pas rejoué
     *                         ({@link Step#reasoningReplayable()})
     * @param contextEdited    le fournisseur a écarté des résultats d'outils pendant le tour
     */
    public record Facts(boolean stepsDropped, boolean reasoningDropped, boolean contextEdited) {

        static final Facts NONE = new Facts(false, false, false);
    }

    /** Relit les faits d'un document rangé ; un document illisible ou absent n'en porte aucun. */
    public static Facts factsOf(String json) {
        if (json == null || json.isBlank()) {
            return Facts.NONE;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            boolean reasoningDropped = false;
            AtelierToolTrace trace = fromJson(json);
            for (Step step : trace.isEmpty() ? List.<Step>of() : trace.steps()) {
                if (step != null && !step.reasoningReplayable()
                        && step.thoughts().stream().anyMatch(t -> t != null && !t.isEmpty())) {
                    reasoningDropped = true;
                    break;
                }
            }
            return new Facts(node.path(DROPPED_STEPS_FIELD).asInt(0) > 0, reasoningDropped,
                    node.path(CONTEXT_EDITED_FIELD).asBoolean(false));
        } catch (com.fasterxml.jackson.core.JacksonException | RuntimeException ex) {
            return Facts.NONE;
        }
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
            for (Thought thought : step.reasoningReplayable() ? step.thoughts() : List.<Thought>of()) {
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
