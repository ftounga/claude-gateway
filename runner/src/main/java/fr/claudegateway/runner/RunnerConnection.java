package fr.claudegateway.runner;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Connexion sortante WSS du runner (F-38 / SF-38-03) vers {@code /runner/ws}, avec heartbeat
 * périodique, reconnexion à backoff plafonné et arrêt propre. Depuis SF-38-04, elle transporte aussi
 * les <b>messages d'outils</b> : les trames reçues sont réellement analysées (champ {@code type}) et
 * les {@code tool_call} / {@code tool_cancel} sont confiés au {@link ToolDispatcher}.
 *
 * <p>La socket est authentifiée par le jeton runner porté en query param (SF-38-02). Un rejet de
 * handshake {@code 401} lève {@link AuthRejectedException} pour que l'appelant efface le jeton
 * périmé. {@link #stop()} (déclenché par {@code Ctrl-C}) ferme la socket (close 1000) et libère la
 * boucle.</p>
 *
 * <p>Toutes les émissions — heartbeat compris — passent par {@link FrameSender} : {@code
 * java.net.http.WebSocket} interdit un {@code sendText} concurrent d'un envoi non terminé.</p>
 *
 * <p>Note : cette classe est intrinsèquement I/O réseau ; elle n'est pas couverte par les tests
 * unitaires (la connexion réelle est un smoke manuel). La logique testable (backoff, config, proxy,
 * jeton, résolution des chemins, outils, file d'émission, aiguillage) est isolée dans des classes dédiées.</p>
 */
public final class RunnerConnection {

    private static final String HEARTBEAT_MESSAGE = "{\"type\":\"heartbeat\"}";

    private final HttpClient httpClient;
    private final RunnerConfig config;
    private final Console console;
    private final Backoff backoff;

    private final TransportFallbackPolicy fallbackPolicy;
    /** Ce qui a été tenté et pourquoi ça a échoué (F-82 / SF-82-03) — il ne décide de rien. */
    private final TransportJournal journal;
    /** La mise à jour du runner (F-111 / SF-111-04) ; nulle hors de RunnerMain (tests). */
    private volatile fr.claudegateway.runner.update.RunnerUpdater updater;
    /**
     * La boucle de mise en service de la Vigie (F-122 / SF-122-06) : lance et maintient le Chrome
     * managé et remonte l'état de readiness. Nulle si le volet Teams est désactivé (--no-teams).
     */
    private volatile fr.claudegateway.runner.teams.VigieLoop vigieLoop;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean fellBackToPolling;
    private volatile WebSocket webSocket;
    private volatile CountDownLatch closedLatch;
    private ScheduledExecutorService heartbeatExecutor;
    private volatile ScheduledFuture<?> heartbeatTask;
    private FrameSender sender;
    private ToolDispatcher dispatcher;
    private FrameRouter router;
    /**
     * L'émetteur de diagnostic (F-132 / SF-132-01) : draine {@link fr.claudegateway.runner.diag.RunnerDiag}
     * par lots et émet la trame {@code runner_diag} sur le WebSocket existant, sur l'exécuteur du
     * heartbeat. Best-effort : n'impacte jamais le runner.
     */
    private volatile fr.claudegateway.runner.diag.RunnerDiagEmitter diagEmitter;

    public RunnerConnection(HttpClient httpClient, RunnerConfig config, Console console) {
        this(httpClient, config, console, new TransportFallbackPolicy(config.transport()));
    }

    /**
     * @param fallbackPolicy decide quand renoncer au WebSocket au profit du repli long-polling
     *                       (F-38 / SF-38-09) ; la boucle s'arrete alors et l'appelant enchaine sur
     *                       {@link PollingConnection}
     */
    public RunnerConnection(HttpClient httpClient, RunnerConfig config, Console console,
            TransportFallbackPolicy fallbackPolicy) {
        this(httpClient, config, console, fallbackPolicy, new TransportJournal());
    }

    /**
     * @param journal consigne le transport tenté, le motif de son échec et ce qui a été retenu
     *                (F-82 / SF-82-03). Il <b>n'influe sur rien</b> : la bascule reste décidée par
     *                {@code fallbackPolicy}, dont ni les seuils ni le comptage ne sont touchés.
     */
    public RunnerConnection(HttpClient httpClient, RunnerConfig config, Console console,
            TransportFallbackPolicy fallbackPolicy, TransportJournal journal) {
        this.httpClient = httpClient;
        this.config = config;
        this.console = console;
        this.fallbackPolicy = fallbackPolicy;
        this.journal = journal;
        this.backoff = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(30));
    }

    /**
     * Vrai si la boucle s'est arretee parce que le WebSocket ne tient pas sur ce reseau : l'appelant
     * doit alors basculer sur le repli long-polling (F-38 / SF-38-09).
     */
    public boolean fellBackToPolling() {
        return fellBackToPolling;
    }

    /**
     * Boucle de connexion bloquante : (re)connecte tant que {@link #stop()} n'a pas été appelé.
     * Lève {@link AuthRejectedException} si le handshake est refusé (jeton périmé).
     */
    public void run(String token) {
        running.set(true);
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "runner-heartbeat");
            t.setDaemon(true);
            return t;
        });
        sender = new FrameSender(console);
        // F-132 / SF-132-01 : le diagnostic du runner est batché et émis sur le WebSocket existant
        // (via la file d'émission), sur l'exécuteur du heartbeat. Best-effort strict : rien ici ne peut
        // casser le runner (l'émetteur avale ses propres erreurs).
        fr.claudegateway.runner.diag.RunnerDiagEmitter emitter =
                new fr.claudegateway.runner.diag.RunnerDiagEmitter(sender::send);
        emitter.start(heartbeatExecutor);
        this.diagEmitter = emitter;
        // F-128 / SF-128-09 : le Chrome managé est créé UNE FOIS ici et PARTAGÉ — la boucle Vigie en
        // pilote le cycle de vie, la pile d'outils (teams_meeting_join) le (re)garantit à la demande.
        // Une seule instance ⇒ pas de double process ; ses méthodes de cycle de vie sont synchronized.
        fr.claudegateway.runner.teams.ManagedChrome chrome = createManagedChrome();
        // Meme montage d'outils que le repli long-polling : les deux transports ne doivent
        // jamais dependre du transport (SF-38-09).
        dispatcher = ToolStack.create(config, console, sender, chrome).dispatcher();
        router = new FrameRouter(dispatcher, console, this::onUpdate);
        // F-122 / SF-122-06 : la boucle Vigie vit avec le runner. Elle est indépendante du transport
        // (elle remonte l'état par son propre POST /runner/vigie/readiness) et démarrée une seule
        // fois ici, sur l'exécuteur du heartbeat, avant la boucle de (re)connexion.
        startVigie(token, chrome);
        URI uri = config.webSocketUri(token);
        String target = safeUri(uri);
        console.info("Cible WebSocket : " + target);
        // Le jeton est déjà expurgé par safeUri : rien de secret n'entre dans le journal.
        journal.attempted(TransportJournal.Transport.WEBSOCKET, target);

        try {
            while (running.get()) {
                CountDownLatch latch = new CountDownLatch(1);
                closedLatch = latch;
                long startedAt = System.nanoTime();
                try {
                    connectOnce(uri, latch);
                    backoff.reset();
                    latch.await(); // attend la fermeture/erreur de la socket
                    // Une socket qui meurt en quelques secondes est la signature d'un proxy qui
                    // coupe l'upgrade : elle compte comme un echec de transport (SF-38-09).
                    Duration lifetime = Duration.ofNanos(System.nanoTime() - startedAt);
                    fallbackPolicy.recordSessionEnded(lifetime);
                    if (lifetime.compareTo(TransportFallbackPolicy.SHORT_SESSION) < 0) {
                        journal.failed(TransportJournal.Transport.WEBSOCKET,
                                "socket coupée après " + lifetime.toMillis() + " ms");
                    }
                } catch (AuthRejectedException e) {
                    // Un jeton refuse n'est pas un probleme de tuyau : aucun repli ne le reparerait.
                    throw e;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (RuntimeException e) {
                    console.warn("Connexion échouée : " + Failures.describeWithHint(e));
                    fallbackPolicy.recordTransportFailure();
                    journal.failed(TransportJournal.Transport.WEBSOCKET, Failures.describe(e));
                }
                if (!running.get()) {
                    break;
                }
                if (fallbackPolicy.shouldFallBack()) {
                    console.warn("WebSocket coupé de façon répétée sur ce réseau — bascule sur le "
                            + "repli long-polling HTTP.");
                    fellBackToPolling = true;
                    break;
                }
                Duration delay = backoff.nextDelay();
                console.warn("Reconnexion dans " + delay.toSeconds() + " s…");
                if (!sleep(delay)) {
                    break;
                }
            }
        } finally {
            stopVigie();
            stopDiag();
            shutdownHeartbeat();
            closeChannel();
        }
        console.info("Boucle de connexion terminée.");
    }

    /** Arrêt propre : ferme la socket (close 1000) et libère la boucle. Idempotent. */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        console.info("Arrêt demandé — fermeture de la connexion…");
        WebSocket ws = this.webSocket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "runner-shutdown");
            } catch (RuntimeException e) {
                ws.abort();
            }
        }
        CountDownLatch latch = this.closedLatch;
        if (latch != null) {
            latch.countDown();
        }
        stopVigie();
        stopDiag();
        shutdownHeartbeat();
    }

    /**
     * Démarre la boucle Vigie (F-122 / SF-122-06) : Chrome managé dédié + remontée périodique de
     * l'état de mise en service, sur l'exécuteur du heartbeat. <b>Best-effort strict</b> : un échec de
     * démarrage n'empêche jamais le runner ni ses autres outils de fonctionner. Sans effet si le volet
     * Teams est désactivé (--no-teams).
     */
    /**
     * Construit l'instance <b>partagée</b> du Chrome managé (F-122 / SF-122-06, réutilisée F-128 /
     * SF-128-09) : profil dédié résolu, lancement et sonde réels. Ne lance <b>rien</b> à la
     * construction — Chrome n'est lancé qu'au premier {@code ensureRunning}. Rend {@code null} quand le
     * volet Teams est désactivé (--no-teams) : la pile d'outils et la Vigie s'en passent alors.
     */
    private fr.claudegateway.runner.teams.ManagedChrome createManagedChrome() {
        if (!config.allowTeams()) {
            return null;
        }
        try {
            fr.claudegateway.runner.teams.ManagedChromeSettings settings =
                    fr.claudegateway.runner.teams.ManagedChromeSettings.resolve(
                            Integer.toString(config.teamsPort()),
                            System.getenv(fr.claudegateway.runner.teams.ChromePaths.PROFILE_ENV),
                            System::getenv);
            return fr.claudegateway.runner.teams.ManagedChrome.real(settings, console::info);
        } catch (RuntimeException e) {
            console.warn("Vigie : Chrome managé non préparé (" + Failures.describe(e)
                    + ") — le reste du runner continue normalement.");
            return null;
        }
    }

    private void startVigie(String token, fr.claudegateway.runner.teams.ManagedChrome chrome) {
        if (!config.allowTeams() || chrome == null) {
            return;
        }
        try {
            int port = config.teamsPort();
            // Coutures SF-122-03 : à l'expiration, la fenêtre managée surgit pour le login ; une fois
            // reconnecté, elle se remasque. Le runner ne se connecte jamais à la place de l'utilisateur.
            fr.claudegateway.runner.teams.VigieSonde sonde =
                    fr.claudegateway.runner.teams.VigieSonde.real(port, console::info,
                            chrome::reveal, chrome::remask);
            fr.claudegateway.runner.teams.VigieReadinessUploader uploader =
                    (token == null || token.isBlank())
                            ? fr.claudegateway.runner.teams.VigieReadinessUploader.unavailable(
                                    "ce poste n'a pas de jeton runner : l'état ne peut pas remonter")
                            : fr.claudegateway.runner.teams.VigieReadinessUploader.over(
                                    httpClient, config.gatewayBaseUrl(), token);
            fr.claudegateway.runner.teams.VigieLoop loop =
                    new fr.claudegateway.runner.teams.VigieLoop(chrome, sonde, uploader,
                            fr.claudegateway.runner.teams.ManagedChrome.currentSystem(),
                            console::info)
                            // SF-122-07 : maintenir un onglet Teams ouvert (le rouvrir s'il a été fermé),
                            // pour que « Teams connecté » passe au vert sans réunion et que le Radar observe.
                            .withTabGuard(fr.claudegateway.runner.teams.TeamsTabOpener.real(
                                    port, console::info));
            loop.start(heartbeatExecutor);
            this.vigieLoop = loop;
            console.info("Vigie : boucle de mise en service démarrée (Chrome managé dédié + remontée "
                    + "de l'état). La check-list de mise en service reflétera l'état réel du poste.");
        } catch (RuntimeException e) {
            console.warn("Vigie : démarrage impossible (" + Failures.describe(e)
                    + ") — le reste du runner continue normalement.");
        }
    }

    /**
     * Arrête l'émetteur de diagnostic (F-132 / SF-132-01) et tente un dernier drainage best-effort.
     * Idempotent ; ne lève jamais.
     */
    private void stopDiag() {
        fr.claudegateway.runner.diag.RunnerDiagEmitter emitter = this.diagEmitter;
        if (emitter == null) {
            return;
        }
        try {
            emitter.stop();
            emitter.flush();
        } catch (RuntimeException e) {
            // Le diagnostic ne casse jamais l'arrêt du runner.
        }
        this.diagEmitter = null;
    }

    /** Arrête la boucle Vigie et le Chrome managé (pas d'orphelin, F-122 / SF-122-06). Idempotent. */
    private void stopVigie() {
        fr.claudegateway.runner.teams.VigieLoop loop = this.vigieLoop;
        if (loop != null) {
            try {
                loop.stop();
            } catch (RuntimeException e) {
                console.warn("Vigie : arrêt imparfait (" + Failures.describe(e) + ").");
            }
            this.vigieLoop = null;
        }
    }

    private void connectOnce(URI uri, CountDownLatch latch) {
        console.info("Connexion en cours…");
        CompletableFuture<WebSocket> future = httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .buildAsync(uri, new Listener(latch));
        WebSocket ws;
        try {
            ws = future.join();
            this.webSocket = ws;
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof WebSocketHandshakeException handshake
                    && handshake.getResponse().statusCode() == 401) {
                throw new AuthRejectedException("Jeton refusé par la gateway (401)");
            }
            throw new RunnerException(Failures.describe(cause), cause);
        }
        console.info("Runner connecté.");
        journal.established(TransportJournal.Transport.WEBSOCKET);
        // F-111 / SF-111-05 : la liaison tient — le lanceur confirme une version à l'essai.
        fr.claudegateway.runner.launcher.LauncherWatch.reportConnected(System.getenv());
        // La file d'émission est branchée sur la socket courante avant toute trame sortante.
        sender.attach(frame -> ws.sendText(frame, true));
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
        startHeartbeat();
    }

    private void startHeartbeat() {
        // Une reconnexion ne doit pas empiler un second ordonnancement sur le premier.
        ScheduledFuture<?> previous = this.heartbeatTask;
        if (previous != null) {
            previous.cancel(false);
        }
        this.heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (running.get()) {
                // Passe par la file d'émission : le heartbeat ne doit jamais entrer en concurrence
                // avec un tool_result parti d'un thread worker.
                sender.send(HEARTBEAT_MESSAGE);
                console.info("Heartbeat envoyé.");
            }
        }, config.heartbeatInterval().toSeconds(), config.heartbeatInterval().toSeconds(), TimeUnit.SECONDS);
    }

    private void shutdownHeartbeat() {
        ScheduledExecutorService executor = this.heartbeatExecutor;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void closeChannel() {
        ToolDispatcher currentDispatcher = this.dispatcher;
        if (currentDispatcher != null) {
            currentDispatcher.close();
        }
        FrameSender currentSender = this.sender;
        if (currentSender != null) {
            currentSender.close();
        }
    }

    /** La mise à jour branchée sur ce transport, pour la reporter sur le repli (F-111 / SF-111-04). */
    public fr.claudegateway.runner.update.RunnerUpdater updater() {
        return updater;
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


    private static String safeUri(URI uri) {
        // On masque le jeton dans l'affichage.
        String s = uri.toString();
        int idx = s.indexOf("token=");
        return idx < 0 ? s : s.substring(0, idx) + "token=***";
    }

    /**
     * Listener WebSocket : analyse le champ {@code type} de chaque trame (Jackson) et aiguille les
     * messages d'outils. Une trame de type inconnu est <b>ignorée en silence</b> — c'est la règle de
     * compatibilité ascendante du contrat, qui permet à un runner ancien de cohabiter avec une
     * gateway plus récente (et l'inverse).
     */
    private final class Listener implements WebSocket.Listener {
        private final CountDownLatch latch;
        private final StringBuilder buffer = new StringBuilder();

        private Listener(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String payload = buffer.toString();
                buffer.setLength(0);
                handle(payload);
            }
            ws.request(1);
            return null;
        }

        private void handle(String payload) {
            // Aiguillage commun aux deux transports : une trame produit le meme effet qu'elle
            // arrive par la socket ou par le repli long-polling (SF-38-09).
            router.route(payload);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            console.warn("Connexion fermée par la gateway (" + statusCode + " " + reason + ").");
            releaseChannel();
            latch.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            console.warn("Erreur de connexion : " + Failures.describe(error));
            releaseChannel();
            latch.countDown();
        }

        /** Socket perdue : plus rien à émettre, et les appels en vol sont abandonnés (contrat §7). */
        private void releaseChannel() {
            sender.detach();
            dispatcher.abortAll();
        }
    }

    /** Handshake refusé (jeton périmé/révoqué) : l'appelant doit effacer le jeton. */
    public static final class AuthRejectedException extends RuntimeException {
        public AuthRejectedException(String message) {
            super(message);
        }
    }
}
