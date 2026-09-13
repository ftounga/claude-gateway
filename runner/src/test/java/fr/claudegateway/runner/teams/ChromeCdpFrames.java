package fr.claudegateway.runner.teams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>Un Chrome de papier qui parle en trames brutes</b> (F-89 / SF-89-08).
 *
 * <p>Constat du 2026-09-13 : 28 réponses observées, 0 classée. Les faux existants appellent les écouteurs
 * directement, avec des événements déjà découpés : ils ne pouvaient rien dire de ce qui arrive vraiment
 * par la socket. Celui-ci émet le <b>texte JSON</b> d'un événement tel que Chrome l'envoie en mode aplati
 * — {@code sessionId} au niveau de la trame, en-têtes, {@code fromServiceWorker}, requête dans l'adresse —
 * et le fait passer par le <b>vrai</b> routage de {@link WebSocketCdpConnection#dispatch(String)}.</p>
 *
 * <p>Il tient aussi les <b>règles de Chrome</b> qui décident de ce qu'on voit :</p>
 * <ul>
 *   <li>une cible n'émet d'événements réseau que si {@code Network.enable} a été envoyé <b>sur sa
 *       session</b> ;</li>
 *   <li>les enfants d'une cible (cadre, worker, service worker) ne sont annoncés
 *       ({@code Target.attachedToTarget}) que si {@code Target.setAutoAttach} a été envoyé <b>sur la
 *       session de leur parent</b> — l'onglet pour ses enfants directs, la cible elle-même pour ses
 *       petits-enfants.</li>
 * </ul>
 */
final class ChromeCdpFrames implements CdpConnection {

    /** Session de l'onglet : la socket est ouverte sur lui, ses événements ne portent pas de session. */
    static final String TAB = "";

    private final ObjectMapper mapper = new ObjectMapper();
    /** Le vrai routage des trames, sans socket : seuls ses écouteurs et {@code dispatch} servent. */
    private final WebSocketCdpConnection socket = new WebSocketCdpConnection();
    private final Set<String> networkEnabled = new LinkedHashSet<>();
    private final Set<String> autoAttached = new LinkedHashSet<>();
    private final Set<String> announced = new LinkedHashSet<>();
    private final Map<String, Target> targets = new LinkedHashMap<>();
    private final Map<String, String> bodies = new HashMap<>();
    private final List<String> sent = new ArrayList<>();
    private boolean open = true;
    private int requests;

    private record Target(String sessionId, String parent, String type, String url) {
    }

    // ------------------------------------------------------------------ CdpConnection

    @Override
    public JsonNode send(String method, ObjectNode params) {
        return send(TAB, method, params);
    }

    @Override
    public JsonNode send(String sessionId, String method, ObjectNode params) {
        CdpCommands.assertAllowed(method);
        String session = sessionId == null ? TAB : sessionId.strip();
        sent.add(session + '|' + method);
        if (CdpCommands.NETWORK_ENABLE.equals(method)) {
            networkEnabled.add(session);
        } else if (CdpCommands.SET_AUTO_ATTACH.equals(method)) {
            autoAttached.add(session);
            targets.values().stream().filter(target -> target.parent().equals(session)).toList()
                    .forEach(this::announce);
        } else if (CdpCommands.GET_RESPONSE_BODY.equals(method)) {
            ObjectNode result = mapper.createObjectNode();
            result.put("body", bodies.getOrDefault(session + '|' + params.path("requestId").asText(""), ""));
            result.put("base64Encoded", false);
            return result;
        }
        return mapper.createObjectNode();
    }

    @Override
    public void onEvent(String method, Consumer<JsonNode> listener) {
        socket.onEvent(method, listener);
    }

    @Override
    public void onSessionEvent(String method, BiConsumer<String, JsonNode> listener) {
        socket.onSessionEvent(method, listener);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
    }

    // ------------------------------------------------------------------ ce que la page fait

    /**
     * Une cible naît sous un parent ({@link #TAB} ou la session d'une autre cible). Elle n'est annoncée
     * que si l'auto-attach a été demandé sur la session de son parent — maintenant ou plus tard.
     */
    ChromeCdpFrames spawn(String parent, String sessionId, String type, String url) {
        Target target = new Target(sessionId, parent, type, url);
        targets.put(sessionId, target);
        if (autoAttached.contains(parent)) {
            announce(target);
        }
        return this;
    }

    /**
     * La réponse à une requête de la cible {@code session}, telle que Chrome l'écrit sur la socket — ou
     * rien, si le réseau n'a pas été activé sur cette session.
     *
     * @return vrai si la trame a été émise
     */
    boolean respond(String session, String url, String body) {
        return respond(session, url, "Fetch", "application/json", 200, body, false);
    }

    boolean respond(String session, String url, String type, String mime, int status, String body,
            boolean fromServiceWorker) {
        if (!networkEnabled.contains(session)) {
            return false;
        }
        String requestId = (session.isEmpty() ? "1234" : session) + '.' + (++requests);
        ObjectNode frame = mapper.createObjectNode();
        frame.put("method", "Network.responseReceived");
        ObjectNode params = frame.putObject("params");
        params.put("requestId", requestId);
        params.put("loaderId", session.isEmpty() ? "7F3A0C2E9B1D" : "");
        params.put("timestamp", 81234.567 + requests);
        params.put("type", type);
        ObjectNode response = params.putObject("response");
        response.put("url", url);
        response.put("status", status);
        response.put("statusText", "");
        ObjectNode headers = response.putObject("headers");
        headers.put("content-type", mime + "; charset=utf-8");
        headers.put("set-cookie", "authtoken=SECRET-COOKIE-DE-SESSION; HttpOnly");
        headers.put("x-ms-request-id", "0f4e1b7c-2a6d-4c1e-9b8a-3d5f6e7a8b9c");
        response.put("mimeType", mime);
        response.put("charset", "utf-8");
        response.put("connectionReused", true);
        response.put("connectionId", 412);
        response.put("remoteIPAddress", "52.113.194.132");
        response.put("remotePort", 443);
        response.put("fromDiskCache", false);
        response.put("fromServiceWorker", fromServiceWorker);
        response.put("fromPrefetchCache", false);
        response.put("encodedDataLength", 2048);
        response.put("protocol", "h2");
        response.put("securityState", "secure");
        params.put("hasExtraInfo", true);
        if (session.isEmpty()) {
            params.put("frameId", "7F3A0C2E9B1D");
        } else {
            frame.put("sessionId", session);
        }
        if (body != null) {
            bodies.put(session + '|' + requestId, body);
        }
        socket.dispatch(frame.toString());
        return true;
    }

    private void announce(Target target) {
        if (!announced.add(target.sessionId())) {
            return;
        }
        ObjectNode frame = mapper.createObjectNode();
        frame.put("method", "Target.attachedToTarget");
        ObjectNode params = frame.putObject("params");
        params.put("sessionId", target.sessionId());
        ObjectNode info = params.putObject("targetInfo");
        info.put("targetId", "T-" + target.sessionId());
        info.put("type", target.type());
        info.put("title", target.url());
        info.put("url", target.url());
        info.put("attached", true);
        info.put("canAccessOpener", false);
        params.put("waitingForDebugger", false);
        if (!target.parent().isEmpty()) {
            frame.put("sessionId", target.parent());
        }
        socket.dispatch(frame.toString());
    }

    // ------------------------------------------------------------------ constats

    boolean announced(String sessionId) {
        return announced.contains(sessionId);
    }

    List<String> sent() {
        return List.copyOf(sent);
    }
}
