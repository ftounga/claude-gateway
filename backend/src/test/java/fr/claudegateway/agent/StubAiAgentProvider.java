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

    public void reset() {
        script.clear();
        lastRequest = null;
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

    @Override
    public AgentTurn nextTurn(AgentTurnRequest request) {
        this.lastRequest = request;
        AgentTurn next = script.poll();
        if (next != null) {
            return next;
        }
        // Script épuisé : renvoyer un tour final par défaut (évite une boucle infinie en test).
        return new AgentTurn("(fin)", List.of(), true, 1, 1);
    }
}
