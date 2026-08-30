package fr.claudegateway.runner.channel;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.RunnerIdentity;

/**
 * Routeur d'appels d'outils vers le runner d'un workspace (F-38 / SF-38-05, contrat de messages §7).
 *
 * <p>C'est <b>ici</b> que vivent les sockets : le {@link RunnerRegistry} ne porte que la
 * <i>présence</i> (il en fabrique aussi pour des connexions hébergées par un autre replica, sans
 * socket), donc {@link RunnerConnection} n'est pas modifié. Le dispatcher tient deux cartes :</p>
 * <ul>
 *   <li>{@code workspaceId -> session décorée} — alimentée à l'établissement, purgée à la fermeture ;</li>
 *   <li>{@code id -> appel en vol} — l'{@code id} est l'identifiant {@code tool_use} du fournisseur.</li>
 * </ul>
 *
 * <p><b>Multi-replica (contrat §8)</b> : le routage n'utilise que {@link RunnerRegistry#findLocal},
 * jamais {@code isConnected()}. Un runner présent sur l'autre pod fait échouer l'appel
 * immédiatement en {@code runner_not_on_this_node} — aucun relais inter-pods n'existe en v1.</p>
 *
 * <p><b>Écriture concurrente</b> : une {@code WebSocketSession} Spring n'est pas thread-safe ; toute
 * émission passe par le même {@link ConcurrentWebSocketSessionDecorator}, y compris le
 * {@code heartbeat_ack} du handler.</p>
 *
 * <p><b>Aucun rejeu</b> : un appel perdu avec la socket devient une erreur rendue au modèle. Rejouer
 * un {@code write_file} serait destructeur.</p>
 */
@Component
public class RunnerCallDispatcher {

    /** Clé sous laquelle la session décorée est déposée dans les attributs de session WS. */
    public static final String OUTBOUND_SESSION_ATTRIBUTE = "runnerOutboundSession";

    /** Clé sous laquelle le canal d'émission ({@link RunnerOutbound}) est déposé dans la session WS. */
    public static final String OUTBOUND_CHANNEL_ATTRIBUTE = "runnerOutboundChannel";

    /**
     * Grâce ajoutée au {@code timeoutMs} de la trame avant que le backend abandonne (contrat §6).
     * Valeur imposée par le contrat ; la propriété n'existe que pour raccourcir l'attente en test.
     */
    public static final long DEFAULT_GRACE_MS = 5_000L;

    private static final Logger log = LoggerFactory.getLogger(RunnerCallDispatcher.class);

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_BYTES = 524_288;
    /** Agrégat de flux conservé par le backend (contrat §5) — exploité par SF-38-07. */
    private static final int MAX_STREAM_BYTES = 131_072;
    private static final int MAX_ERROR_MESSAGE_CHARS = 500;
    /** Un runner SF-38-03 n'envoie pas de {@code ready} : on suppose les outils fichiers (contrat §2.1). */
    private static final Set<String> DEFAULT_CAPABILITIES = Set.of("files");

    private final RunnerRegistry registry;
    private final ObjectMapper objectMapper;
    private final long graceMs;

    private final Map<UUID, RunnerOutbound> outbound = new ConcurrentHashMap<>();
    private final Map<String, InFlightCall> inFlight = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> capabilities = new ConcurrentHashMap<>();

    public RunnerCallDispatcher(RunnerRegistry registry, ObjectMapper objectMapper,
            @Value("${app.runner.call.grace-ms:5000}") long graceMs) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.graceMs = graceMs > 0 ? graceMs : DEFAULT_GRACE_MS;
    }

    // ------------------------------------------------------------ cycle de vie

    /**
     * Enveloppe la session dans un décorateur sérialisant les écritures et la retient pour ce
     * workspace. Le décorateur est aussi déposé dans les attributs de la session : le
     * {@code heartbeat_ack} emprunte ainsi exactement la même instance (contrat §7).
     */
    public void attach(WebSocketSession session, RunnerIdentity identity) {
        WebSocketSession decorated =
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_BYTES);
        WebSocketRunnerOutbound channel = new WebSocketRunnerOutbound(decorated);
        session.getAttributes().put(OUTBOUND_SESSION_ATTRIBUTE, decorated);
        session.getAttributes().put(OUTBOUND_CHANNEL_ATTRIBUTE, channel);
        outbound.put(identity.workspaceId(), channel);
    }

    /**
     * Branche un canal de <b>repli long-polling</b> (F-38 / SF-38-09) sur ce workspace : le
     * dispatcher émet ses {@code tool_call} de la même façon, ils attendent simplement dans une file
     * qu'un {@code POST /runner/poll} vienne les chercher. Un canal précédent (socket WS ou polling
     * plus ancien) est remplacé — c'est le même runner qui change de tuyau.
     */
    public void attachChannel(RunnerIdentity identity, RunnerOutbound channel) {
        outbound.put(identity.workspaceId(), channel);
    }

    /**
     * Débranche un canal (quel que soit son transport) et termine ses appels en vol. Ne fait rien si
     * le canal courant du workspace n'est plus celui-ci : une connexion plus récente ne doit pas être
     * effacée par la fin tardive de l'ancienne (garde anti-course, même esprit que
     * {@link RunnerRegistry#unregister}).
     */
    public void detachChannel(UUID workspaceId, RunnerOutbound channel) {
        if (!outbound.remove(workspaceId, channel)) {
            return;
        }
        capabilities.remove(workspaceId);
        failAllOf(workspaceId);
    }

    /**
     * Retire la session de ce workspace et termine <b>tous ses appels en vol</b> en
     * {@code runner_unavailable}. Appelée avant {@code registry.unregister} : au moment où la
     * présence disparaît, plus aucun appel n'attend une socket morte.
     *
     * <p>La carte n'est purgée que si l'entrée est bien celle de cette session : une reconnexion plus
     * récente ne doit pas être effacée par la fermeture tardive de l'ancienne (même garde
     * anti-course que {@link RunnerRegistry#unregister}).</p>
     */
    public void detach(WebSocketSession session, RunnerIdentity identity) {
        session.getAttributes().remove(OUTBOUND_SESSION_ATTRIBUTE);
        Object stored = session.getAttributes().remove(OUTBOUND_CHANNEL_ATTRIBUTE);
        if (stored instanceof RunnerOutbound channel) {
            // Fermeture d'une socket déjà remplacée par une reconnexion (ou par un repli
            // long-polling) : detachChannel ne casse rien de la connexion vivante.
            detachChannel(identity.workspaceId(), channel);
        }
    }

    /**
     * Session à utiliser pour écrire vers ce runner : le décorateur si la connexion est passée par
     * {@link #attach}, la session brute sinon (aucune écriture ne doit être perdue faute de
     * décorateur).
     */
    public WebSocketSession outboundFor(WebSocketSession session) {
        Object decorated = session.getAttributes().get(OUTBOUND_SESSION_ATTRIBUTE);
        return decorated instanceof WebSocketSession ws ? ws : session;
    }

    // ---------------------------------------------------------------- émission

    /**
     * Émet un {@code tool_call} vers le runner du workspace et <b>attend</b> son {@code tool_result}.
     * Bloquant par construction : la boucle tool-use est séquentielle et ne peut pas continuer sans
     * le résultat.
     *
     * @param workspaceId workspace ciblé (déjà vérifié possédé par l'appelant — isolation)
     * @param callId      identifiant de corrélation (= {@code tool_use} du fournisseur), non vide
     * @param tool        nom d'outil, exactement celui exposé au modèle (aucun préfixe)
     * @param input       arguments, copiés verbatim dans la trame
     * @param timeoutMs   délai armé côté runner ; le backend attend {@code timeoutMs + 5 000 ms}
     * @return l'issue de l'appel, jamais {@code null} — une erreur de transport est une issue
     */
    public RunnerCallResult call(UUID workspaceId, String callId, String tool, JsonNode input,
            long timeoutMs) {
        return call(workspaceId, callId, tool, input, timeoutMs, null);
    }

    /**
     * Variante avec <b>relais de flux</b> (SF-38-07) : chaque {@code chunk} d'un {@code tool_stream}
     * est remis à {@code onChunk} au fil de l'eau, en plus d'être agrégé. Utilisé par {@code bash}
     * pour que la sortie d'une commande apparaisse dans la session pendant qu'elle tourne, au lieu
     * d'arriver d'un bloc à la fin.
     *
     * <p>Le consommateur est appelé sur le thread de réception WebSocket : il ne doit ni bloquer ni
     * lever. Il est <b>détaché</b> dès que le résultat est traité, pour qu'aucun fragment tardif ne
     * parte alors que l'appelant a repris la main.</p>
     */
    public RunnerCallResult call(UUID workspaceId, String callId, String tool, JsonNode input,
            long timeoutMs, java.util.function.Consumer<String> onChunk) {
        if (registry.findLocal(workspaceId).isEmpty()) {
            // isConnected() peut être vrai cross-replica : la socket vit alors sur l'autre pod.
            return RunnerCallResult.backendError(registry.isConnected(workspaceId)
                    ? RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE
                    : RunnerErrorCodes.RUNNER_UNAVAILABLE);
        }
        RunnerOutbound session = outbound.get(workspaceId);
        if (session == null || !session.isOpen()) {
            return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE);
        }
        if (!capabilitiesOf(workspaceId).contains(capabilityFor(tool))) {
            return RunnerCallResult.backendError(RunnerErrorCodes.UNSUPPORTED_TOOL);
        }

        InFlightCall pending = new InFlightCall(workspaceId, new CompletableFuture<>(), onChunk);
        if (inFlight.putIfAbsent(callId, pending) != null) {
            return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_PROTOCOL_ERROR,
                    "Identifiant d'appel déjà utilisé.");
        }
        try {
            session.send(toolCallFrame(callId, tool, input, timeoutMs));
        } catch (IOException | RuntimeException ex) {
            inFlight.remove(callId);
            log.debug("Émission tool_call impossible (workspace={}, outil={})", workspaceId, tool);
            return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE);
        }

        try {
            return pending.future().get(timeoutMs + graceMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            sendQuietly(session, cancelFrame(callId, "timeout"));
            log.info("Runner silencieux (workspace={}, outil={}) : appel abandonné", workspaceId, tool);
            return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_TIMEOUT);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            sendQuietly(session, cancelFrame(callId, "user_interrupt"));
            // Appel perdu comme s'il était parti avec la socket : aucune reprise, aucun rejeu.
            return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE,
                    "Appel interrompu avant la réponse du runner.");
        } catch (ExecutionException ex) {
            return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_PROTOCOL_ERROR);
        } finally {
            // Tout résultat tardif portant cet id sera jeté : il n'est plus en vol.
            inFlight.remove(callId);
        }
    }

    /**
     * Demande l'annulation de tous les appels en vol d'un workspace (F-38 / SF-38-07, interruption
     * utilisateur — même geste que F-32). Émet un {@code tool_cancel} par appel ; le runner tue le
     * processus et émet <b>quand même</b> sa trame terminale (contrat §2.5), qui débloque l'appelant
     * normalement. Le backend ne complète donc rien lui-même : pas de résultat inventé.
     *
     * @return le nombre d'appels effectivement visés
     */
    public int cancelWorkspace(UUID workspaceId, String reason) {
        RunnerOutbound session = outbound.get(workspaceId);
        if (session == null) {
            return 0;
        }
        int cancelled = 0;
        for (Map.Entry<String, InFlightCall> entry : inFlight.entrySet()) {
            if (entry.getValue().workspaceId().equals(workspaceId)) {
                sendQuietly(session, cancelFrame(entry.getKey(), reason));
                cancelled++;
            }
        }
        if (cancelled > 0) {
            log.info("Annulation demandée sur {} appel(s) en vol (workspace={}, motif={})", cancelled,
                    workspaceId, reason);
        }
        return cancelled;
    }

    /**
     * <b>Coupe la liaison</b> avec le runner d'un workspace, sur-le-champ (F-38 / SF-38-08) :
     * annulation des appels en vol, fermeture de la socket, puis terminaison de ce qui attendait
     * encore. Utilisé par la révocation d'un jeton et par le coupe-circuit.
     *
     * <p>Sans cette coupure, révoquer un jeton ne révoquait rien tant que la socket restait
     * ouverte : le runner continuait de servir les appels d'une connexion pourtant retirée. Les
     * appels en vol sont terminés ici même, sans attendre {@code afterConnectionClosed} — au moment
     * où l'on coupe, plus rien ne doit attendre une socket condamnée.</p>
     *
     * @return vrai si une socket locale a effectivement été fermée
     */
    public boolean disconnect(UUID workspaceId, String reason) {
        RunnerOutbound session = outbound.get(workspaceId);
        if (session == null) {
            return false;
        }
        cancelWorkspace(workspaceId, reason);
        // Selon le transport : fermeture de la socket, ou fermeture du canal long-polling (qui se
        // retire lui-même du registre et débranche ses appels en vol).
        session.close();
        failAllOf(workspaceId);
        log.info("Liaison runner coupée (workspace={}, motif={})", workspaceId, reason);
        return true;
    }

    /** Jeton du runner <b>local</b> de ce workspace, s'il y en a un (audit, révocation ciblée). */
    public java.util.Optional<UUID> localTokenId(UUID workspaceId) {
        return registry.findLocal(workspaceId).map(RunnerConnection::tokenId);
    }

    // ---------------------------------------------------------------- réception

    /**
     * Aiguille une trame entrante autre que le heartbeat (contrat §2). L'identité vient
     * <b>toujours</b> de la session (jamais d'un champ du message) : une trame ne peut pas terminer
     * l'appel d'un autre workspace. Toute trame inattendue est ignorée en silence — c'est ce qui
     * permet à un runner plus ancien de cohabiter avec un backend plus récent (contrat §0).
     */
    public void onFrame(RunnerIdentity identity, String type, JsonNode frame) {
        switch (type == null ? "" : type) {
            case "ready" -> onReady(identity, frame);
            case "tool_result" -> onToolResult(identity, frame);
            case "tool_stream" -> onToolStream(identity, frame);
            case "protocol_error" -> onProtocolError(identity, frame);
            default -> log.debug("Trame runner de type inconnu ignorée (workspace={})",
                    identity.workspaceId());
        }
    }

    private void onReady(RunnerIdentity identity, JsonNode frame) {
        Set<String> declared = new HashSet<>();
        JsonNode node = frame.path("capabilities");
        if (node.isArray()) {
            node.forEach(entry -> {
                if (entry.isTextual() && !entry.asText().isBlank()) {
                    declared.add(entry.asText());
                }
            });
        }
        capabilities.put(identity.workspaceId(), declared.isEmpty() ? DEFAULT_CAPABILITIES : declared);
        log.debug("Runner prêt (workspace={}, capacités={})", identity.workspaceId(), declared);
    }

    private void onToolResult(RunnerIdentity identity, JsonNode frame) {
        InFlightCall pending = pendingFor(identity, frame);
        if (pending == null) {
            return;
        }
        // Détaché AVANT de rendre la main : plus aucun fragment ne peut être relayé une fois que
        // l'appelant a repris (le flux SSE n'est pas thread-safe, et l'appel est terminé).
        pending.detachRelay();
        pending.future().complete(parseResult(frame, pending));
    }

    private void onToolStream(RunnerIdentity identity, JsonNode frame) {
        InFlightCall pending = pendingFor(identity, frame);
        if (pending == null) {
            return; // Flux arrivé après le résultat (ou id inconnu) : jeté, contrat §2.3.
        }
        String chunk = frame.path("chunk").asText("");
        if (!chunk.isEmpty()) {
            pending.appendStream(chunk);
            pending.relay(chunk);
        }
    }

    private void onProtocolError(RunnerIdentity identity, JsonNode frame) {
        log.warn("protocol_error reçu du runner (workspace={}, code={})", identity.workspaceId(),
                frame.path("code").asText("?"));
        InFlightCall pending = pendingFor(identity, frame);
        if (pending != null) {
            pending.detachRelay();
            pending.future().complete(
                    RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_PROTOCOL_ERROR));
        }
    }

    /**
     * Appel en vol référencé par la trame, ou {@code null} s'il n'y en a pas — id absent, id inconnu,
     * ou appel appartenant à un <b>autre workspace</b> que celui de la session (isolation).
     */
    private InFlightCall pendingFor(RunnerIdentity identity, JsonNode frame) {
        String id = frame.path("id").asText(null);
        if (id == null || id.isBlank()) {
            return null;
        }
        InFlightCall pending = inFlight.get(id);
        if (pending == null) {
            log.debug("Trame runner sans appel en vol (workspace={})", identity.workspaceId());
            return null;
        }
        if (!pending.workspaceId().equals(identity.workspaceId())) {
            log.warn("Trame runner rattachée à un autre workspace : ignorée");
            return null;
        }
        return pending;
    }

    /** Traduit un {@code tool_result} en {@link RunnerCallResult}, en refusant toute forme non conforme. */
    private RunnerCallResult parseResult(JsonNode frame, InFlightCall pending) {
        boolean ok = frame.path("ok").asBoolean(false);
        if (ok && !frame.path("content").isTextual()) {
            // `content` est une STRING obligatoire quand ok=true (contrat §2.4) : tout le reste est
            // une réponse non conforme, pas un contenu vide implicite.
            return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_PROTOCOL_ERROR);
        }
        long durationMs = Math.max(0L, frame.path("durationMs").asLong(0L));
        Integer exitCode = frame.path("exitCode").isNumber() ? frame.path("exitCode").asInt() : null;
        Long bytes = frame.path("bytes").isNumber() ? frame.path("bytes").asLong() : null;
        if (ok) {
            return new RunnerCallResult(true, frame.path("content").asText(),
                    frame.path("truncated").asBoolean(false), exitCode, durationMs, bytes, null, null,
                    pending.streamedText(), pending.streamTruncated());
        }
        String code = frame.path("error").path("code").asText("");
        String message = frame.path("error").path("message").asText("");
        if (code.isBlank()) {
            return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_PROTOCOL_ERROR);
        }
        return new RunnerCallResult(false, "", false, exitCode, durationMs, bytes, code,
                shorten(message.isBlank() ? "Opération refusée par le runner." : message),
                pending.streamedText(), pending.streamTruncated());
    }

    // ------------------------------------------------------------------ outils

    private Set<String> capabilitiesOf(UUID workspaceId) {
        return capabilities.getOrDefault(workspaceId, DEFAULT_CAPABILITIES);
    }

    /** Capacité requise par un outil (contrat §2.1) : {@code bash} pour la commande, sinon fichiers. */
    private static String capabilityFor(String tool) {
        return "bash".equals(tool) ? "bash" : "files";
    }

    private String toolCallFrame(String callId, String tool, JsonNode input, long timeoutMs) {
        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("type", "tool_call");
        frame.put("id", callId);
        frame.put("tool", tool);
        frame.set("input", input == null || !input.isObject() ? objectMapper.createObjectNode() : input);
        frame.put("timeoutMs", timeoutMs);
        return frame.toString();
    }

    private String cancelFrame(String callId, String reason) {
        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("type", "tool_cancel");
        frame.put("id", callId);
        frame.put("reason", reason);
        return frame.toString();
    }

    /** Émission « best effort » : une annulation qui n'arrive pas ne doit pas masquer l'erreur initiale. */
    private void sendQuietly(RunnerOutbound session, String payload) {
        try {
            session.send(payload);
        } catch (IOException | RuntimeException ex) {
            log.debug("Émission d'annulation impossible : socket déjà indisponible");
        }
    }

    /** Termine tous les appels en vol d'un workspace (socket fermée). */
    private void failAllOf(UUID workspaceId) {
        inFlight.forEach((id, pending) -> {
            if (pending.workspaceId().equals(workspaceId)) {
                pending.detachRelay();
                pending.future().complete(
                        RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE));
            }
        });
    }

    private static String shorten(String message) {
        return message.length() <= MAX_ERROR_MESSAGE_CHARS
                ? message
                : message.substring(0, MAX_ERROR_MESSAGE_CHARS);
    }

    /**
     * Appel en vol : le workspace qui l'a émis (garde d'isolation à la réception), la promesse de
     * résultat, et l'agrégat des trames {@code tool_stream} reçues avant le résultat.
     */
    private record InFlightCall(UUID workspaceId, CompletableFuture<RunnerCallResult> future,
            StringBuilder stream, AtomicBoolean truncatedFlag,
            java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>> relay) {

        InFlightCall(UUID workspaceId, CompletableFuture<RunnerCallResult> future,
                java.util.function.Consumer<String> onChunk) {
            this(workspaceId, future, new StringBuilder(), new AtomicBoolean(false),
                    new java.util.concurrent.atomic.AtomicReference<>(onChunk));
        }

        /** Relaie un fragment au consommateur, s'il est encore branché. Ne propage jamais d'échec. */
        void relay(String chunk) {
            java.util.function.Consumer<String> consumer = relay.get();
            if (consumer == null) {
                return;
            }
            try {
                consumer.accept(chunk);
            } catch (RuntimeException ex) {
                // Client parti, flux clos : la sortie continue d'être agrégée pour le modèle.
                relay.set(null);
            }
        }

        void detachRelay() {
            relay.set(null);
        }

        void appendStream(String chunk) {
            synchronized (stream) {
                if (stream.length() >= MAX_STREAM_BYTES) {
                    truncatedFlag.set(true);
                    return;
                }
                int room = MAX_STREAM_BYTES - stream.length();
                if (chunk.length() > room) {
                    stream.append(chunk, 0, room);
                    truncatedFlag.set(true);
                } else {
                    stream.append(chunk);
                }
            }
        }

        String streamedText() {
            synchronized (stream) {
                return stream.toString();
            }
        }

        boolean streamTruncated() {
            return truncatedFlag.get();
        }
    }
}
