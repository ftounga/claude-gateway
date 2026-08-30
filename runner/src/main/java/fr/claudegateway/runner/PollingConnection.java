package fr.claudegateway.runner;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Boucle de <b>repli long-polling</b> du runner (F-38 / SF-38-09), utilisée quand un proxy
 * d'entreprise tue le WebSocket.
 *
 * <p>Elle transporte <b>les mêmes enveloppes</b> que la socket : {@code ready} à l'ouverture,
 * {@code tool_call} / {@code tool_cancel} en entrée, {@code tool_stream} / {@code tool_result} /
 * {@code protocol_error} en sortie. Aucun type de message nouveau, aucune garde en moins — le même
 * {@link ToolStack} monte le même confinement et les mêmes exclusions.</p>
 *
 * <p><b>Le poll est le heartbeat</b> : la gateway rafraîchit {@code last_seen_at} à chaque poll, donc
 * aucun minuteur séparé n'est armé ici. Et comme les outils s'exécutent sur les threads workers du
 * {@link ToolDispatcher}, un appel long ne suspend jamais le poll (contrat §6).</p>
 */
public final class PollingConnection {

    /** Attente demandée à la gateway pour un poll ; borne serveur : {@code app.runner.poll.max-wait-ms}. */
    static final long POLL_WAIT_MS = 25_000L;

    private static final String FALLBACK_VERSION = "0.0.1";

    private final PollingTransport transport;
    private final RunnerConfig config;
    private final Console console;
    private final Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(30));
    private final AtomicBoolean running = new AtomicBoolean(false);

    private FrameSender sender;
    private ToolDispatcher dispatcher;

    public PollingConnection(PollingTransport transport, RunnerConfig config, Console console) {
        this.transport = transport;
        this.config = config;
        this.console = console;
    }

    /**
     * Boucle bloquante : poste les trames sortantes et réclame les entrantes jusqu'à {@link #stop()}.
     *
     * @throws RunnerConnection.AuthRejectedException si le jeton est refusé (l'appelant l'efface)
     */
    public void run() {
        running.set(true);
        sender = new FrameSender(console);
        dispatcher = ToolStack.create(config, console, sender).dispatcher();
        FrameRouter router = new FrameRouter(dispatcher, console);

        // Chaque trame sortante est un POST : la file mono-thread de FrameSender garantit qu'elles
        // partent dans l'ordre, exactement comme sur la socket.
        sender.attach(frame -> {
            try {
                transport.send(List.of(frame));
                return CompletableFuture.completedFuture(null);
            } catch (IOException | RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        });

        console.info("Repli long-polling actif : " + config.pollUrl());
        sender.send(dispatcher.readyFrame(runnerVersion()));

        try {
            loop(router);
        } finally {
            transport.disconnect();
            dispatcher.close();
            sender.close();
        }
        console.info("Boucle de repli terminée.");
    }

    /** Arrêt propre : le poll en cours rend la main au plus tard au bout de son délai. Idempotent. */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            console.info("Arrêt demandé — fermeture du repli long-polling…");
        }
    }

    private void loop(FrameRouter router) {
        while (running.get()) {
            List<String> frames;
            try {
                frames = transport.poll(POLL_WAIT_MS);
                backoff.reset();
            } catch (PollingTransport.ChannelClosedException e) {
                // La gateway a coupé (coupe-circuit, balayage) : repoller n'y changerait rien.
                console.warn(e.getMessage());
                return;
            } catch (IOException e) {
                if (!running.get()) {
                    return;
                }
                Duration delay = backoff.nextDelay();
                console.warn("Long-poll impossible (" + e.getMessage() + ") — nouvelle tentative dans "
                        + delay.toSeconds() + " s…");
                if (!sleep(delay)) {
                    return;
                }
                continue;
            }
            for (String frame : frames) {
                router.route(frame);
            }
        }
    }

    private boolean sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String runnerVersion() {
        String version = PollingConnection.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? FALLBACK_VERSION : version;
    }
}
