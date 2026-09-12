package fr.claudegateway.runner.teams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Un navigateur de papier (F-87 / SF-87-02).
 *
 * <p>Il existe parce que nous n'avons ni compte Teams de test, ni Chrome dans l'intégration
 * continue : il n'en fallait pas moins pouvoir vérifier qu'un corps indisponible produit un manque,
 * qu'une commande interdite est refusée, et qu'aucun en-tête ne franchit l'observation.</p>
 */
final class FakeCdpConnection implements CdpConnection {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Consumer<JsonNode>> listeners = new HashMap<>();
    private final Map<String, String> bodies = new HashMap<>();
    private final List<String> sent = new ArrayList<>();

    /** Identifiants de requête dont le corps doit être déclaré indisponible. */
    private final List<String> purged = new ArrayList<>();

    private boolean open = true;
    private boolean scrollMoves = true;

    @Override
    public JsonNode send(String method, ObjectNode params) {
        CdpCommands.assertAllowed(method);
        sent.add(method);
        if (CdpCommands.GET_RESPONSE_BODY.equals(method)) {
            String requestId = params.path("requestId").asText("");
            if (purged.contains(requestId)) {
                throw new IllegalStateException("No resource with given identifier found");
            }
            ObjectNode result = mapper.createObjectNode();
            result.put("body", bodies.getOrDefault(requestId, ""));
            result.put("base64Encoded", false);
            return result;
        }
        if (CdpCommands.EVALUATE.equals(method)) {
            ObjectNode result = mapper.createObjectNode();
            result.putObject("result").put("value", scrollMoves);
            return result;
        }
        return mapper.createObjectNode();
    }

    @Override
    public void onEvent(String method, Consumer<JsonNode> listener) {
        listeners.put(method, listener);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
    }

    // ------------------------------------------------------------------ pilotage du faux

    /** Fait « arriver » une réponse, en-têtes compris — précisément ce qu'on ne doit pas lire. */
    void emitResponse(String requestId, String url, String body) {
        ObjectNode params = mapper.createObjectNode();
        params.put("requestId", requestId);
        ObjectNode response = params.putObject("response");
        response.put("url", url);
        response.put("status", 200);
        response.put("mimeType", "application/json");
        ObjectNode headers = response.putObject("headers");
        headers.put("Set-Cookie", "authtoken=SECRET-COOKIE-DE-SESSION; HttpOnly");
        headers.put("Authorization", "Bearer SECRET-JETON-DE-SESSION");
        headers.put("x-skypetoken", "SECRET-SKYPETOKEN-DE-SESSION");
        if (body != null) {
            bodies.put(requestId, body);
        }
        Consumer<JsonNode> listener = listeners.get("Network.responseReceived");
        if (listener != null) {
            listener.accept(params);
        }
    }

    void purge(String requestId) {
        purged.add(requestId);
    }

    void scrollStopsMoving() {
        scrollMoves = false;
    }

    List<String> sentCommands() {
        return List.copyOf(sent);
    }
}
