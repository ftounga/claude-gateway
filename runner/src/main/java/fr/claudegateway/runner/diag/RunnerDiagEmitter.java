package fr.claudegateway.runner.diag;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.diag.RunnerDiag.Drained;

/**
 * <b>L'émetteur batché</b> des événements de diagnostic (F-132 / SF-132-01) : il draine
 * {@link RunnerDiag} <b>par lots</b> (~toutes les quelques secondes) et émet une trame
 * {@code runner_diag} sur le <b>WebSocket existant</b> — même chemin que le heartbeat / les
 * {@code tool_result}, via la file d'émission ({@code FrameSender}). Aucun nouveau transport, aucun
 * spam : rien à drainer → aucune trame.
 *
 * <p><b>Best-effort strict.</b> Il est planifié sur l'exécuteur du heartbeat, qu'un jet non capturé
 * arrêterait : {@link #flush} n'échoue jamais. Une socket absente abandonne simplement le lot (les
 * événements ont déjà été retirés de l'anneau — on ne rejoue pas ; l'anneau borné se remplira à
 * nouveau). La journalisation ne doit jamais perturber le runner.</p>
 *
 * <p>La trame :</p>
 * <pre>{@code
 * {"type":"runner_diag",
 *  "events":[{"ts":"<ISO-8601>","level":"INFO","cat":"chrome","code":"chrome_state",
 *             "msg":"...","fields":{"state":"REACHABLE","port":9222}}],
 *  "dropped":0}
 * }</pre>
 */
public final class RunnerDiagEmitter {

    /** Période par défaut entre deux drainages (cadrage §5 : « toutes les quelques secondes »). */
    public static final long DEFAULT_PERIOD_SECONDS = 5L;
    /** Nombre max d'événements par trame : borne la taille d'une trame (le reste part au prochain). */
    public static final int MAX_BATCH = 200;

    private final Consumer<String> send;
    private final long periodSeconds;
    private final ObjectMapper mapper = new ObjectMapper();

    private ScheduledFuture<?> task;

    public RunnerDiagEmitter(Consumer<String> send) {
        this(send, DEFAULT_PERIOD_SECONDS);
    }

    RunnerDiagEmitter(Consumer<String> send, long periodSeconds) {
        this.send = send;
        this.periodSeconds = periodSeconds;
    }

    /** Planifie le drainage périodique sur l'exécuteur donné (celui du heartbeat). Idempotent. */
    public synchronized void start(ScheduledExecutorService executor) {
        if (task != null) {
            return;
        }
        task = executor.scheduleWithFixedDelay(this::flush, periodSeconds, periodSeconds,
                TimeUnit.SECONDS);
    }

    /** Arrête le drainage périodique (un dernier drainage possible via {@link #flush}). Idempotent. */
    public synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }

    /**
     * Un drainage : retire un lot et l'émet en une trame. Ne fait rien s'il n'y a rien à dire. Ne
     * lève jamais (best-effort — protège l'exécuteur du heartbeat partagé).
     */
    public void flush() {
        try {
            if (RunnerDiag.isEmpty()) {
                return;
            }
            Drained drained = RunnerDiag.drain(MAX_BATCH);
            if (drained.events().isEmpty() && drained.dropped() == 0) {
                return;
            }
            String frame = write(drained);
            if (frame != null && send != null) {
                send.accept(frame);
            }
        } catch (RuntimeException ignored) {
            // La journalisation ne casse jamais le heartbeat ni le runner.
        }
    }

    String write(Drained drained) {
        ObjectNode frame = mapper.createObjectNode();
        frame.put("type", "runner_diag");
        ArrayNode events = frame.putArray("events");
        for (RunnerDiagEvent e : drained.events()) {
            ObjectNode node = events.addObject();
            node.put("ts", e.ts() == null ? null : e.ts().toString());
            node.put("level", e.level() == null ? null : e.level().name());
            node.put("cat", e.cat());
            node.put("code", e.code());
            if (e.msg() != null) {
                node.put("msg", e.msg());
            }
            ObjectNode fields = node.putObject("fields");
            for (Map.Entry<String, Object> entry : e.fields().entrySet()) {
                putScalar(fields, entry.getKey(), entry.getValue());
            }
        }
        if (drained.dropped() > 0) {
            frame.put("dropped", drained.dropped());
        }
        try {
            return mapper.writeValueAsString(frame);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private static void putScalar(ObjectNode node, String key, Object value) {
        if (value instanceof Integer i) {
            node.put(key, i);
        } else if (value instanceof Long l) {
            node.put(key, l);
        } else if (value instanceof Double d) {
            node.put(key, d);
        } else if (value instanceof Number n) {
            node.put(key, n.longValue());
        } else if (value instanceof Boolean b) {
            node.put(key, b);
        } else if (value != null) {
            node.put(key, value.toString());
        }
    }
}
