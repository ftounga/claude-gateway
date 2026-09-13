package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>Le travail de la synchro du soir sur la machine</b> (F-100 / SF-100-02) : un seul à la fois, en tâche
 * de fond, qui bat et rend compte.
 *
 * <p>La gateway lance, le runner <b>accepte et rend la main</b> : la collecte dure (défilement des fils,
 * pagination, première synchro sur 30 jours) et ne bloque ni les terminaux ni les commandes du poste.
 * Le travail envoie un battement au moins toutes les 60 s ; si la gateway répond que la synchro est close
 * (annulée, abandonnée), il <b>s'arrête</b> et n'envoie plus rien.</p>
 */
public final class RadarSyncAgent {

    /** Battement au moins aussi souvent : la gateway abandonne une synchro muette depuis 15 min. */
    static final long HEARTBEAT_SECONDS = 60;
    /** Tentatives d'envoi de la fin : la fin est ce qui libère le poste côté gateway. */
    static final int FINISH_ATTEMPTS = 3;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RadarUplink uplink;
    private final Supplier<RadarCollector> collector;
    private final Executor executor;
    private final ScheduledExecutorService ticker;
    private final Consumer<String> say;

    private String runningSyncId;

    /**
     * @param ticker battement périodique ; {@code null} : seuls les battements de la collecte partent
     */
    RadarSyncAgent(RadarUplink uplink, Supplier<RadarCollector> collector, Executor executor,
            ScheduledExecutorService ticker, Consumer<String> say) {
        this.uplink = uplink;
        this.collector = collector;
        this.executor = executor;
        this.ticker = ticker;
        this.say = say == null ? line -> { } : say;
    }

    /** Ce que le runner répond à la gateway. */
    record Acceptance(boolean accepted, String reason, String runningSyncId) {

        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode node = mapper.createObjectNode();
            node.put("accepted", accepted);
            if (reason != null) {
                node.put("reason", reason);
            }
            if (runningSyncId != null) {
                node.put("running_sync_id", runningSyncId);
            }
            return node;
        }
    }

    /** Accepte une synchro et la lance en tâche de fond — ou dit pourquoi pas. */
    synchronized Acceptance accept(RadarAssignment assignment) {
        if (runningSyncId != null) {
            return new Acceptance(false, "BUSY", runningSyncId);
        }
        if (!uplink.available()) {
            return new Acceptance(false, "NO_UPLINK", null);
        }
        runningSyncId = assignment.syncId();
        try {
            executor.execute(() -> run(assignment));
        } catch (RuntimeException e) {
            runningSyncId = null;
            return new Acceptance(false, "NOT_STARTED", null);
        }
        return new Acceptance(true, null, null);
    }

    /** La synchro en cours sur ce poste, ou {@code null}. */
    synchronized String runningSyncId() {
        return runningSyncId;
    }

    private void run(RadarAssignment assignment) {
        Context context = new Context(assignment.syncId());
        ScheduledFuture<?> beat = null;
        try {
            say.accept("Radar : synchro " + assignment.trigger().toLowerCase(java.util.Locale.ROOT)
                    + " démarrée (tâche de fond).");
            if (!context.progress("start", 0, 0)) {
                return;
            }
            if (ticker != null) {
                beat = ticker.scheduleAtFixedRate(context::beat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS,
                        TimeUnit.SECONDS);
            }
            RadarCollector.Outcome outcome;
            try {
                outcome = collector.get().collect(assignment, context);
            } catch (RuntimeException e) {
                ObjectNode coverage = mapper.createObjectNode();
                ObjectNode failure = coverage.putObject("failure");
                failure.put("code", "COLLECTOR_ERROR");
                failure.put("sentence", "La collecte s'est arrêtée sur une erreur inattendue du runner.");
                outcome = new RadarCollector.Outcome("FAILED", coverage);
            }
            if (context.stopped()) {
                say.accept("Radar : synchro close côté gateway, arrêt.");
                return;
            }
            finish(assignment.syncId(), outcome);
        } finally {
            if (beat != null) {
                beat.cancel(false);
            }
            synchronized (this) {
                runningSyncId = null;
            }
        }
    }

    private void finish(String syncId, RadarCollector.Outcome outcome) {
        ObjectNode body = mapper.createObjectNode();
        body.put("status", outcome.status());
        body.set("coverage", outcome.coverage() == null ? mapper.createObjectNode() : outcome.coverage());
        for (int attempt = 1; attempt <= FINISH_ATTEMPTS; attempt++) {
            try {
                uplink.finish(syncId, body);
                say.accept("Radar : synchro terminée (" + outcome.status() + ").");
                return;
            } catch (IOException e) {
                if (attempt == FINISH_ATTEMPTS) {
                    // La gateway abandonnera la synchro faute de battement : l'échec reste visible.
                    say.accept("Radar : la fin de synchro n'a pas pu remonter (" + e.getMessage() + ").");
                }
            }
        }
    }

    /** Le contexte d'une synchro : battement, lots, arrêt. */
    private final class Context implements RadarSyncContext {

        private final String syncId;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private volatile String phase = "start";
        private volatile int done;
        private volatile int total;

        Context(String syncId) {
            this.syncId = syncId;
        }

        @Override
        public boolean progress(String newPhase, int newDone, int newTotal) {
            this.phase = newPhase == null ? "" : newPhase;
            this.done = Math.max(0, newDone);
            this.total = Math.max(0, newTotal);
            return beat();
        }

        boolean beat() {
            if (stopped.get()) {
                return false;
            }
            ObjectNode body = mapper.createObjectNode();
            body.put("phase", phase);
            body.put("done", done);
            body.put("total", total);
            try {
                if (uplink.progress(syncId, body) == RadarUplink.Answer.STOPPED) {
                    stopped.set(true);
                    return false;
                }
            } catch (IOException e) {
                // Un battement perdu n'arrête pas la collecte : le suivant rattrapera.
            }
            return true;
        }

        @Override
        public JsonNode submit(ObjectNode body) throws IOException {
            if (stopped.get()) {
                ObjectNode answer = mapper.createObjectNode();
                answer.put("status", "STOPPED");
                return answer;
            }
            JsonNode answer = uplink.batch(syncId, body);
            if ("STOPPED".equals(answer.path("status").asText(""))) {
                stopped.set(true);
            }
            return answer;
        }

        @Override
        public boolean stopped() {
            return stopped.get();
        }
    }
}
