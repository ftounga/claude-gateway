package fr.claudegateway.runner.teams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Un onglet de papier qui parle aussi au nom de ses cibles attachées (F-100 / SF-100-00) : il porte
 * la session de chaque événement, et note chaque commande avec la session à laquelle elle a été
 * adressée.
 */
final class SurveyFakeBrowser implements CdpConnection {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, List<BiConsumer<String, JsonNode>>> listeners = new HashMap<>();
    /** « session|méthode » ; session vide pour l'onglet. */
    private final List<String> sent = new ArrayList<>();
    private boolean open = true;

    @Override
    public JsonNode send(String method, ObjectNode params) {
        return send("", method, params);
    }

    @Override
    public JsonNode send(String sessionId, String method, ObjectNode params) {
        CdpCommands.assertAllowed(method);
        sent.add((sessionId == null ? "" : sessionId) + "|" + method);
        return mapper.createObjectNode();
    }

    @Override
    public void onEvent(String method, Consumer<JsonNode> listener) {
        onSessionEvent(method, (session, params) -> listener.accept(params));
    }

    @Override
    public void onSessionEvent(String method, BiConsumer<String, JsonNode> listener) {
        listeners.computeIfAbsent(method, key -> new ArrayList<>()).add(listener);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
    }

    void lose() {
        open = false;
    }

    /** Une réponse, en-têtes secrets compris, requête comprise — ce qui ne doit JAMAIS être noté. */
    void respond(String sessionId, String url, String type, String mime, int status) {
        ObjectNode params = mapper.createObjectNode();
        params.put("requestId", "r" + sent.size());
        params.put("type", type);
        ObjectNode response = params.putObject("response");
        response.put("url", url);
        response.put("status", status);
        response.put("mimeType", mime);
        ObjectNode headers = response.putObject("headers");
        headers.put("Set-Cookie", "authtoken=SECRET-COOKIE-DE-SESSION; HttpOnly");
        headers.put("Authorization", "Bearer SECRET-JETON-DE-SESSION");
        emit(sessionId, "Network.responseReceived", params);
    }

    /** Une cible qui s'attache à l'onglet. */
    void attach(String sessionId, String type, String url) {
        ObjectNode params = mapper.createObjectNode();
        params.put("sessionId", sessionId);
        ObjectNode info = params.putObject("targetInfo");
        info.put("type", type);
        info.put("url", url);
        emit("", "Target.attachedToTarget", params);
    }

    /** Une socket WebSocket ouverte (F-89 / SF-89-05), requête empoisonnée comprise. */
    void socket(String sessionId, String requestId, String url) {
        ObjectNode params = mapper.createObjectNode();
        params.put("requestId", requestId);
        params.put("url", url);
        emit(sessionId, "Network.webSocketCreated", params);
    }

    /** Une trame reçue — son contenu ne doit JAMAIS être noté. */
    void frame(String sessionId, String requestId, String payload) {
        ObjectNode params = mapper.createObjectNode();
        params.put("requestId", requestId);
        params.putObject("response").put("opcode", 1).put("payloadData", payload);
        emit(sessionId, "Network.webSocketFrameReceived", params);
    }

    private void emit(String sessionId, String method, JsonNode params) {
        listeners.getOrDefault(method, List.of()).forEach(listener -> listener.accept(sessionId, params));
    }

    List<String> sent() {
        return List.copyOf(sent);
    }
}
