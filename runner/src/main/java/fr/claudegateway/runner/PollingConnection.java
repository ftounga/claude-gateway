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
 * {@code protocol_error} en sortie. Aucun type de message nouveau, aucun comportement en moins — le
 * même {@link ToolStack} monte les mêmes outils, avec le même dossier de départ par projet.</p>
 *
 * <p><b>Le poll est le heartbeat</b> : la gateway rafraîchit {@code last_seen_at} à chaque poll, donc
 * aucun minuteur séparé n'est armé ici. Et comme les outils s'exécutent sur les threads workers du
 * {@link ToolDispatcher}, un appel long ne suspend jamais le poll (contrat §6).</p>
 */
public final class PollingConnection {

    /** Attente demandée à la gateway pour un poll ; borne serveur : {@code app.runner.poll.max-wait-ms}. */
    static final long POLL_WAIT_MS = 25_000L;


    private final PollingTransport transport;
    private final RunnerConfig config;
    private final Console console;
    private final Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(30));
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Ce qui a été tenté et pourquoi ça a échoué (F-82 / SF-82-03) — il ne décide de rien. */
    private final TransportJournal journal;
    /** La mise à jour du runner (F-111 / SF-111-04) ; nulle hors de RunnerMain (tests). */
    private volatile fr.claudegateway.runner.update.RunnerUpdater updater;

    private FrameSender sender;
    private ToolDispatcher dispatcher;

    public PollingConnection(PollingTransport transport, RunnerConfig config, Console console) {
        this(transport, config, console, new TransportJournal());
    }

    /**
     * @param journal consigne le transport tenté et le motif de son échec (F-82 / SF-82-03). Il
     *                n'influe sur rien : la boucle de repli est inchangée.
     */
    public PollingConnection(PollingTransport transport, RunnerConfig config, Console console,
            TransportJournal journal) {
        this.transport = transport;
        this.config = config;
        this.console = console;
        this.journal = journal;
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
        FrameRouter router = new FrameRouter(dispatcher, console, this::onUpdate);

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
        // L'URL de poll ne porte JAMAIS le jeton — il voyage en en-tête X-Runner-Token.
        journal.attempted(TransportJournal.Transport.POLLING, config.pollUrl());
        // F-111 : la version réelle, et la présence du lanceur (SF-111-02) — sans lui, aucune mise à
        // jour d'un clic.
        // F-111 / SF-111-04 : les nouvelles de la mise à jour partent par le transport du moment.
        fr.claudegateway.runner.update.RunnerUpdater currentUpdater = this.updater;
        if (currentUpdater != null) {
            currentUpdater.attach(sender);
        }
        // F-111 / SF-111-05 : un retour arrière du lanceur est dit à la gateway dans cette trame.
        com.fasterxml.jackson.databind.JsonNode lastUpdate =
                fr.claudegateway.runner.launcher.LauncherWatch.pendingReport(System.getenv());
        sender.send(dispatcher.readyFrame(RunnerBuild.current(),
                fr.claudegateway.runner.launcher.LauncherWatch.underLauncher(System.getenv()), lastUpdate));
        if (lastUpdate != null) {
            fr.claudegateway.runner.launcher.LauncherWatch.clearReport(System.getenv());
        }

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
                // Un poll qui aboutit EST la preuve que ce transport porte la session.
                journal.established(TransportJournal.Transport.POLLING);
                // F-111 / SF-111-05 : la liaison tient — le lanceur confirme une version à l'essai.
                fr.claudegateway.runner.launcher.LauncherWatch.reportConnected(System.getenv());
            } catch (PollingTransport.ChannelClosedException e) {
                // La gateway a coupé (coupe-circuit, balayage) : repoller n'y changerait rien.
                console.warn(e.getMessage());
                journal.failed(TransportJournal.Transport.POLLING, e.getMessage());
                return;
            } catch (IOException e) {
                journal.failed(TransportJournal.Transport.POLLING, Failures.describe(e));
                if (!running.get()) {
                    return;
                }
                Duration delay = backoff.nextDelay();
                console.warn("Long-poll impossible (" + Failures.describe(e) + ") — nouvelle tentative dans "
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

    /** Branche la mise à jour du runner (F-111 / SF-111-04) sur ce transport. */
    public void withUpdater(fr.claudegateway.runner.update.RunnerUpdater value) {
        this.updater = value;
    }

    private void onUpdate(com.fasterxml.jackson.databind.JsonNode frame) {
        fr.claudegateway.runner.update.RunnerUpdater current = this.updater;
        if (current != null) {
            current.onUpdate(frame);
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

}
