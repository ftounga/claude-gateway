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
