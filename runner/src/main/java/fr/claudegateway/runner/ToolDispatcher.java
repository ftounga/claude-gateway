package fr.claudegateway.runner;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Aiguillage des messages d'outils du canal runner (F-38 / SF-38-04) : lit les trames
 * {@code tool_call} et {@code tool_cancel}, exécute l'outil sur un <b>thread worker dédié</b> et émet
 * <b>exactement une</b> trame terminale {@code tool_result} par identifiant d'appel.
 *
 * <p>Points du contrat de messages appliqués ici :</p>
 * <ul>
 *   <li>l'identifiant {@code id} est renvoyé <b>verbatim</b> ; le runner n'en invente jamais ;</li>
 *   <li>l'exécution ne se fait jamais sur le thread du heartbeat ni sur celui de réception (§6) ;</li>
 *   <li>le runner arme son propre chronomètre {@code timeoutMs} et répond {@code timeout} (§6) ;</li>
 *   <li>un {@code tool_cancel} termine l'appel en {@code cancelled}, sauf si le résultat est déjà
 *       parti ; un {@code id} inconnu est ignoré en silence (§2.5) ;</li>
 *   <li>une trame illisible produit un {@code protocol_error} et <b>ne ferme pas</b> la socket (§2.6).</li>
 * </ul>
 */
public final class ToolDispatcher implements AutoCloseable {

    /** Valeur imposée par le contrat quand {@code timeoutMs} est absent ou invalide. */
    static final int DEFAULT_TIMEOUT_MS = 30_000;

    /** Longueur maximale de l'identifiant de corrélation (contrat §1). */
    static final int MAX_ID_LENGTH = 64;

    private final ToolExecutor tools;
    private final List<String> capabilities;
    private final FrameSender sender;
    private final Console console;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Call> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService workers;
    private final ScheduledExecutorService clock;

    /** Aiguilleur annonçant les seuls outils fichiers (compatibilité : tests et appels historiques). */
    public ToolDispatcher(ToolExecutor tools, FrameSender sender, Console console) {
        this(tools, List.of("files"), sender, console);
    }

    /**
     * @param capabilities capacités annoncées dans la trame {@code ready} (contrat §2.1) ;
     *                     {@code bash} n'y figure que si la machine l'a autorisé (SF-38-07)
     */
    public ToolDispatcher(ToolExecutor tools, List<String> capabilities, FrameSender sender,
            Console console) {
        this.tools = tools;
        this.capabilities = List.copyOf(capabilities);
        this.sender = sender;
        this.console = console;
        AtomicInteger counter = new AtomicInteger();
        this.workers = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "runner-tool-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.clock = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "runner-tool-timeout");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Trame d'annonce émise juste après l'ouverture de la socket. Le runner y déclare ses capacités
     * réelles : {@code files} toujours, {@code bash} seulement si l'exécution de commandes a été
     * autorisée au démarrage ({@code --allow-bash}, SF-38-07).
     */
    public String readyFrame(String runnerVersion) {
        ObjectNode frame = mapper.createObjectNode();
        frame.put("type", "ready");
        frame.put("protocol", 1);
        frame.put("runnerVersion", runnerVersion);
        com.fasterxml.jackson.databind.node.ArrayNode declared = frame.putArray("capabilities");
        capabilities.forEach(declared::add);
        frame.put("os", System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT));
        return write(frame);
    }

    /** Traite une trame {@code tool_call}. Ne lève jamais. */
    public void onToolCall(JsonNode frame) {
        String id = text(frame, "id");
        if (id == null || id.isBlank() || id.length() > MAX_ID_LENGTH) {
            sendProtocolError("invalid_envelope", "Champ id manquant ou invalide.", null);
            return;
        }
        String tool = text(frame, "tool");
        if (tool == null || tool.isBlank()) {
            sendProtocolError("invalid_envelope", "Champ tool manquant.", id);
            return;
        }
        int timeoutMs = frame.path("timeoutMs").asInt(0);
        if (timeoutMs <= 0) {
            timeoutMs = DEFAULT_TIMEOUT_MS;
        }
        JsonNode input = frame.get("input");

        Call call = new Call(id);
        if (inFlight.putIfAbsent(id, call) != null) {
            // Identifiant déjà en vol : le contrat garantit son unicité, on ne rejoue rien.
            return;
        }
        int effectiveTimeoutMs = timeoutMs;
        try {
            call.worker = workers.submit(() -> run(call, tool, input, effectiveTimeoutMs));
            call.deadline = clock.schedule(() -> onTimeout(call), timeoutMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            complete(call, ToolOutcome.error("internal", "Runner en cours d'arrêt."));
        }
    }

    /** Traite une trame {@code tool_cancel} ; un identifiant inconnu est ignoré. */
    public void onToolCancel(JsonNode frame) {
        String id = text(frame, "id");
        if (id == null || id.isBlank()) {
            return;
        }
        Call call = inFlight.get(id);
        if (call == null) {
            return;
        }
        // L'issue est réservée AVANT l'interruption (voir onTimeout) : même raison, même ordre.
        complete(call, ToolOutcome.error("cancelled", "Appel interrompu."));
        interrupt(call);
    }

    /** Émet un {@code protocol_error} (§2.6) ; l'identifiant est facultatif. */
    public void sendProtocolError(String code, String message, String id) {
        ObjectNode frame = mapper.createObjectNode();
        frame.put("type", "protocol_error");
        frame.put("code", code);
        frame.put("message", message);
        if (id != null && !id.isBlank()) {
            frame.put("id", id);
        }
        console.warn("Trame refusée (" + code + ") : " + message);
        sender.send(write(frame));
    }

    /**
     * Socket perdue : les appels en vol sont abandonnés sans émettre de résultat (aucun rejeu,
     * contrat §7) — la gateway conclura d'elle-même à un runner indisponible.
     */
    public void abortAll() {
        for (Call call : inFlight.values()) {
            call.done.set(true);
            interrupt(call);
        }
        inFlight.clear();
    }

    @Override
    public void close() {
        abortAll();
        workers.shutdownNow();
        clock.shutdownNow();
    }

    private void run(Call call, String tool, JsonNode input, int timeoutMs) {
        ToolOutcome outcome;
        try {
            outcome = tools.execute(tool, input, new CallContext(call, timeoutMs));
        } catch (RuntimeException e) {
            outcome = ToolOutcome.error("internal", "Erreur interne du runner.");
        } finally {
            // Le drapeau d'interruption ne doit pas contaminer le prochain appel du pool.
            Thread.interrupted();
        }
        complete(call, outcome);
    }

    /**
     * Échéance atteinte : on <b>réserve l'issue avant</b> d'interrompre le worker.
     *
     * <p>L'ordre inverse est une course : {@code interrupt} réveille le worker, qui rend son propre
     * {@code cancelled} (c'est ce que fait {@code BashTool} sur {@code InterruptedException}) et peut
     * gagner le {@code compareAndSet} de {@link #complete} — la gateway verrait alors une annulation
     * là où le délai a bel et bien été dépassé. En publiant d'abord, le drapeau terminal est pris :
     * le worker réveillé ne peut plus lui substituer la sienne.</p>
     *
     * <p>L'interruption suit dans tous les cas, y compris quand le {@code compareAndSet} a échoué :
     * aucun thread ne doit rester à travailler pour un appel déjà terminé.</p>
     */
    private void onTimeout(Call call) {
        complete(call, ToolOutcome.error("timeout", "Délai d'exécution dépassé."));
        interrupt(call);
    }

    private void complete(Call call, ToolOutcome outcome) {
        if (!call.done.compareAndSet(false, true)) {
            return; // trame terminale déjà émise pour cet identifiant
        }
        inFlight.remove(call.id, call);
        ScheduledFuture<?> deadline = call.deadline;
        if (deadline != null) {
            deadline.cancel(false);
        }
        long durationMs = Math.max(0, (System.nanoTime() - call.startedAt) / 1_000_000);
        sender.send(resultFrame(call.id, outcome, durationMs));
        console.info("Outil " + (outcome.ok() ? "exécuté" : "en erreur (" + outcome.errorCode() + ")")
                + " en " + durationMs + " ms.");
    }

    private void interrupt(Call call) {
        Future<?> worker = call.worker;
        if (worker != null) {
            worker.cancel(true);
        }
    }

    private String resultFrame(String id, ToolOutcome outcome, long durationMs) {
        ObjectNode frame = mapper.createObjectNode();
        frame.put("type", "tool_result");
        frame.put("id", id);
        frame.put("ok", outcome.ok());
        if (outcome.ok()) {
            frame.put("content", outcome.content());
            frame.put("truncated", outcome.truncated());
            if (outcome.bytes() >= 0) {
                frame.put("bytes", outcome.bytes());
            }
            if (outcome.exitCode() != null) {
                // Présent uniquement pour `bash` (contrat §2.4) : c'est l'information qui dit si la
                // commande a réussi, indépendamment du fait qu'elle ait bien tourné.
                frame.put("exitCode", outcome.exitCode());
            }
        } else {
            ObjectNode error = frame.putObject("error");
            error.put("code", outcome.errorCode());
            error.put("message", outcome.errorMessage());
        }
        frame.put("durationMs", durationMs);
        return write(frame);
    }

    /**
     * Trame {@code tool_stream} (contrat §2.3). Le {@code seq} est <b>partagé</b> entre {@code stdout}
     * et {@code stderr} du même appel : l'ordre des {@code seq} est l'ordre réel d'émission, ce qui
     * permet à la gateway de reconstituer l'entrelacement des deux flux.
     */
    private String streamFrame(String id, int seq, String stream, String chunk) {
        ObjectNode frame = mapper.createObjectNode();
        frame.put("type", "tool_stream");
        frame.put("id", id);
        frame.put("seq", seq);
        frame.put("stream", stream);
        frame.put("chunk", chunk);
        return write(frame);
    }

    private String write(ObjectNode frame) {
        try {
            return mapper.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            // Ne peut pas arriver sur un ObjectNode construit ici ; repli minimal et honnête.
            return "{\"type\":\"protocol_error\",\"code\":\"unparsable\",\"message\":\"Sérialisation impossible.\"}";
        }
    }

    private static String text(JsonNode frame, String field) {
        JsonNode value = frame == null ? null : frame.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    /**
     * Contexte remis à l'outil : c'est par lui que {@code bash} diffuse sa sortie, sans rien savoir
     * du transport. Une fois la trame terminale émise, plus aucun fragment ne part (contrat §2.3 :
     * tous les {@code tool_stream} d'un {@code id} précèdent son {@code tool_result}).
     */
    private final class CallContext implements ToolContext {
        private final Call call;
        private final int timeoutMs;

        private CallContext(Call call, int timeoutMs) {
            this.call = call;
            this.timeoutMs = timeoutMs;
        }

        @Override
        public void stream(String stream, String chunk) {
            if (call.done.get() || chunk == null || chunk.isEmpty()) {
                return;
            }
            sender.send(streamFrame(call.id, call.seq.getAndIncrement(), stream, chunk));
        }

        @Override
        public long timeoutMs() {
            return timeoutMs;
        }

        @Override
        public boolean cancelled() {
            return call.done.get();
        }
    }

    /** Appel en vol : garde d'unicité de la trame terminale, worker, chronomètre et compteur seq. */
    private static final class Call {
        private final String id;
        private final AtomicBoolean done = new AtomicBoolean(false);
        private final AtomicInteger seq = new AtomicInteger();
        private final long startedAt = System.nanoTime();
        private volatile Future<?> worker;
        private volatile ScheduledFuture<?> deadline;

        private Call(String id) {
            this.id = id;
        }
    }
}
