package fr.claudegateway.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Stub scriptable d'{@link AiAgentProvider} pour les tests : renvoie une séquence prédéfinie de tours,
 * sans réseau. On empile des tours (appels d'outils) puis un tour final (texte).
 */
public class StubAiAgentProvider implements AiAgentProvider {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Deque<AgentTurn> script = new ArrayDeque<>();
    private int idSeq = 0;
    public volatile AgentTurnRequest lastRequest;
    /** Tous les noms d'outils offerts, tous appels confondus — pour vérifier ce qu'a vu une sous-boucle. */
    public final java.util.Set<String> toolNamesSeen = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void reset() {
        script.clear();
        lastRequest = null;
        duringTurn = null;
        toolNamesSeen.clear();
        idSeq = 0;
    }

    /** Empile un tour « appel d'outil » (input clé/valeur). */
    public void enqueueToolCall(String toolName, String... kv) {
        ObjectNode input = mapper.createObjectNode();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            input.put(kv[i], kv[i + 1]);
        }
        List<AgentToolCall> calls = new ArrayList<>();
        calls.add(new AgentToolCall("tool_" + (idSeq++), toolName, input));
        script.add(new AgentTurn("", calls, false, 5, 5));
    }

    /**
     * Empile un tour « appel d'outil » précédé de <b>blocs de raisonnement signés</b> (F-39 /
     * SF-39-10) : c'est la forme que rend le fournisseur quand le raisonnement est actif, et celle
     * que la boucle doit remettre en tête du message assistant rejoué.
     */
    public void enqueueToolCallWithReasoning(String toolName, String signature, String... kv) {
        ObjectNode input = mapper.createObjectNode();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            input.put(kv[i], kv[i + 1]);
        }
        List<AgentToolCall> calls = new ArrayList<>();
        calls.add(new AgentToolCall("tool_" + (idSeq++), toolName, input));
        script.add(new AgentTurn("je regarde", calls, false, 5, 5, false,
                List.of(new AgentContentBlock.Reasoning("", signature))));
    }

    /**
     * Empile un tour « appel d'outil » dont un argument est une <b>structure JSON</b> (tableau ou
     * objet) — ce que la forme clé/valeur ne sait pas exprimer.
     */
    public void enqueueToolCallWithJson(String toolName, String key, String json) {
        ObjectNode input = mapper.createObjectNode();
        try {
            input.set(key, mapper.readTree(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            input.put(key, json); // JSON invalide : on l'envoie tel quel, c'est le cas à tester.
        }
        List<AgentToolCall> calls = new ArrayList<>();
        calls.add(new AgentToolCall("tool_" + (idSeq++), toolName, input));
        script.add(new AgentTurn("", calls, false, 5, 5));
    }

    /**
     * Empile un tour « appel d'outil » <b>sans identifiant</b> : certains fournisseurs peuvent
     * renvoyer un {@code tool_use} sans id exploitable, et la boucle doit alors en fabriquer un
     * (F-38 / SF-38-05, contrat de messages §1).
     */
    public void enqueueToolCallWithoutId(String toolName) {
        List<AgentToolCall> calls = new ArrayList<>();
        calls.add(new AgentToolCall(null, toolName, mapper.createObjectNode()));
        script.add(new AgentTurn("", calls, false, 5, 5));
    }

    /**
     * Empile un tour <b>tronqué</b> (SF-28-18) : le fournisseur a coupé la réponse au plafond de
     * sortie. Le tour porte un texte d'intention <b>et</b> un appel d'outil, comme le fait l'API dans
     * ce cas — la boucle doit refuser d'exécuter ce dernier.
     */
    public void enqueueTruncated(String text, String toolName) {
        List<AgentToolCall> calls = new ArrayList<>();
        calls.add(new AgentToolCall("tool_" + (idSeq++), toolName, mapper.createObjectNode()));
        script.add(new AgentTurn(text, calls, true, 5, 5, true));
    }

    /** Empile un tour final <b>sans aucun texte</b> : le tour n'a rien produit (SF-28-18). */
    public void enqueueEmptyFinal() {
        script.add(new AgentTurn("", List.of(), true, 5, 5));
    }

    /** Empile le tour final (réponse texte, stop). */
    public void enqueueFinal(String text) {
        script.add(new AgentTurn(text, List.of(), true, 5, 5));
    }

    /**
     * Empile un tour « appel d'outil » à la <b>consommation choisie</b> (F-39 / SF-39-15) : le
     * plafond de consommation d'un message se teste sur des itérations dont on connaît le poids.
     */
    public void enqueueToolCallCosting(String toolName, int inputTokens, int outputTokens) {
        List<AgentToolCall> calls = new ArrayList<>();
        calls.add(new AgentToolCall("tool_" + (idSeq++), toolName, mapper.createObjectNode()));
        script.add(new AgentTurn("", calls, false, inputTokens, outputTokens));
    }

    /** Nombre de tours encore en attente : dit ce que la boucle n'a PAS consommé. */
    public int remaining() {
        return script.size();
    }

    /**
     * Action jouée <b>une seule fois</b>, au premier appel du fournisseur : c'est le moyen de
     * simuler ce qui arrive PENDANT un tour — un dépôt de précision, un clic — plutôt qu'avant.
     */
    public void onTurn(Runnable action) {
        this.duringTurn = action;
    }

    private volatile Runnable duringTurn;

    @Override
    public AgentTurn nextTurn(AgentTurnRequest request) {
        this.lastRequest = request;
        Runnable action = duringTurn;
        if (action != null) {
            duringTurn = null;
            action.run();
        }
        if (request.tools() != null) {
            request.tools().forEach(tool -> toolNamesSeen.add(tool.name()));
        }
        AgentTurn next = script.poll();
        if (next != null) {
            return next;
        }
        // Script épuisé : renvoyer un tour final par défaut (évite une boucle infinie en test).
        return new AgentTurn("(fin)", List.of(), true, 1, 1);
    }
}
