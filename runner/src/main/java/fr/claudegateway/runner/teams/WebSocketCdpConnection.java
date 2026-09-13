package fr.claudegateway.runner.teams;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * La socket de débogage, <b>en Java pur</b> (F-87 / SF-87-02).
 *
 * <p>Le protocole de débogage d'un navigateur est du JSON sur une socket : la machine virtuelle sait
 * faire les deux depuis Java 11. C'est ce qui permet de tenir la décision D3 — « le pilotage du
 * navigateur n'est jamais embarqué » — <b>à zéro octet</b> plutôt qu'en téléchargeant une
 * bibliothèque de pilotage de plusieurs dizaines de mégaoctets.</p>
 *
 * <p>Aucune commande ne part sans passer par {@link CdpCommands#assertAllowed(String)} : c'est ici,
 * au point d'émission, que le verrou de sécurité du volet est posé.</p>
 */
public final class WebSocketCdpConnection implements CdpConnection {

    /** Délai d'une commande : au-delà, on ne suppose pas, on échoue. */
    public static final long COMMAND_TIMEOUT_MS = 10_000L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger sequence = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JsonNode>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, List<BiConsumer<String, JsonNode>>> listeners = new ConcurrentHashMap<>();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final StringBuilder incoming = new StringBuilder();
    /**
     * Posée <b>après</b> la construction : l'écouteur de la socket a besoin de l'objet, et l'objet a
     * besoin de la socket. Le nœud se dénoue ici, et pas en fabriquant deux instances — la première
     * recevrait les trames, la seconde serait rendue à l'appelant, et rien ne marcherait.
     */
    private volatile WebSocket socket;

    WebSocketCdpConnection() {
    }

    /** Ouvre la socket de débogage d'un onglet. L'adresse vient de la découverte, jamais de nous. */
    public static WebSocketCdpConnection open(String webSocketDebuggerUrl) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .proxy(java.net.ProxySelector.of(null)) // la boucle locale ne passe par aucun proxy
                .build();
        WebSocketCdpConnection connection = new WebSocketCdpConnection();
        try {
            connection.socket = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create(webSocketDebuggerUrl), connection.new Listener())
                    .get(10, TimeUnit.SECONDS);
            return connection;
        } catch (Exception e) {
            throw new BrowserLinkException(BrowserLinkException.BROWSER_NOT_DETECTED,
                    "La socket de débogage du navigateur n'a pas pu être ouverte. "
                            + BrowserLaunchAdvice.forCurrentSystem(BrowserPort.DEFAULT_PORT), e);
        }
    }

    @Override
    public JsonNode send(String method, ObjectNode params) {
        return send("", method, params);
    }

    /** Même émission, adressée à la session d'une cible attachée quand {@code sessionId} est posé. */
    @Override
    public JsonNode send(String sessionId, String method, ObjectNode params) {
        CdpCommands.assertAllowed(method);
        if (!isOpen()) {
            throw new BrowserLinkException(BrowserLinkException.LINK_LOST,
                    "La liaison avec le navigateur a été perdue. Vérifiez que la fenêtre lancée "
                            + "avec le port de débogage est toujours ouverte.");
        }
        int id = sequence.incrementAndGet();
        ObjectNode frame = mapper.createObjectNode();
        frame.put("id", id);
        frame.put("method", method);
        frame.set("params", params == null ? mapper.createObjectNode() : params);
        if (sessionId != null && !sessionId.isBlank()) {
            frame.put("sessionId", sessionId.strip());
        }

        CompletableFuture<JsonNode> answer = new CompletableFuture<>();
        inFlight.put(id, answer);
        try {
            socket.sendText(frame.toString(), true).get(5, TimeUnit.SECONDS);
            return answer.get(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrowserLinkException(BrowserLinkException.LINK_LOST, "Commande interrompue.");
        } catch (Exception e) {
            throw new BrowserLinkException(BrowserLinkException.LINK_LOST,
                    "Le navigateur n'a pas répondu à « " + method + " ».", e);
        } finally {
            inFlight.remove(id);
        }
    }

    @Override
    public void onEvent(String method, Consumer<JsonNode> listener) {
        onSessionEvent(method, (sessionId, params) -> listener.accept(params));
    }

    @Override
    public void onSessionEvent(String method, BiConsumer<String, JsonNode> listener) {
        listeners.computeIfAbsent(method, key -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @Override
    public boolean isOpen() {
        return open.get() && socket != null && !socket.isOutputClosed();
    }

    @Override
    public void close() {
        open.set(false);
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "fin").exceptionally(error -> null);
        }
        inFlight.values().forEach(future -> future.completeExceptionally(
                new BrowserLinkException(BrowserLinkException.LINK_LOST, "Liaison fermée.")));
        inFlight.clear();
    }

    /** Distribue une trame reçue : résultat d'une commande, ou événement abonné. */
    void dispatch(String text) {
        try {
            JsonNode frame = mapper.readTree(text);
            if (frame.has("id")) {
                CompletableFuture<JsonNode> waiting = inFlight.get(frame.path("id").asInt());
                if (waiting != null) {
                    if (frame.has("error")) {
                        waiting.completeExceptionally(new BrowserLinkException(
                                BrowserLinkException.LINK_LOST,
                                "Le navigateur a refusé la commande : "
                                        + frame.path("error").path("message").asText("")));
                    } else {
                        waiting.complete(frame.path("result"));
                    }
                }
                return;
            }
            String method = frame.path("method").asText("");
            // Aplati (flatten) : l'événement d'une cible attachée porte sa session. On la transmet,
            // pour que l'observation sache d'où vient une réponse — onglet, cadre ou worker.
            String sessionId = frame.path("sessionId").asText("");
            listeners.getOrDefault(method, List.of())
                    .forEach(listener -> listener.accept(sessionId, frame.path("params")));
        } catch (Exception ignored) {
            // Une trame illisible ne ferme pas la liaison : le navigateur en émet beaucoup, et
            // toutes ne nous concernent pas.
        }
    }

    /** Réassemble les messages fragmentés avant de les distribuer. */
    private final class Listener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket,
                CharSequence data, boolean last) {
            incoming.append(data);
            if (last) {
                String text = incoming.toString();
                incoming.setLength(0);
                dispatch(text);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode,
                String reason) {
            open.set(false);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            open.set(false);
        }
    }
}
