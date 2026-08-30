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
 * jeton, confinement, outils, file d'émission, aiguillage) est isolée dans des classes dédiées.</p>
 */
public final class RunnerConnection {

    private static final String HEARTBEAT_MESSAGE = "{\"type\":\"heartbeat\"}";
    private static final String FALLBACK_VERSION = "0.0.1";

    private final HttpClient httpClient;
    private final RunnerConfig config;
    private final Console console;
    private final Backoff backoff;

    private final TransportFallbackPolicy fallbackPolicy;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean fellBackToPolling;
    private volatile WebSocket webSocket;
    private volatile CountDownLatch closedLatch;
    private ScheduledExecutorService heartbeatExecutor;
    private volatile ScheduledFuture<?> heartbeatTask;
    private FrameSender sender;
    private ToolDispatcher dispatcher;
    private FrameRouter router;

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
        this.httpClient = httpClient;
        this.config = config;
        this.console = console;
        this.fallbackPolicy = fallbackPolicy;
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
        // Meme montage de gardes que le repli long-polling : confinement et exclusions ne doivent
        // jamais dependre du transport (SF-38-09).
        dispatcher = ToolStack.create(config, console, sender).dispatcher();
        router = new FrameRouter(dispatcher, console);
        URI uri = config.webSocketUri(token);
        console.info("Cible WebSocket : " + safeUri(uri));

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
                    fallbackPolicy.recordSessionEnded(Duration.ofNanos(System.nanoTime() - startedAt));
                } catch (AuthRejectedException e) {
                    // Un jeton refuse n'est pas un probleme de tuyau : aucun repli ne le reparerait.
                    throw e;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (RuntimeException e) {
                    console.warn("Connexion échouée : " + e.getMessage());
                    fallbackPolicy.recordTransportFailure();
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
        shutdownHeartbeat();
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
            throw new RunnerException(cause.getMessage() == null ? cause.toString() : cause.getMessage(), cause);
        }
        console.info("Runner connecté.");
        // La file d'émission est branchée sur la socket courante avant toute trame sortante.
        sender.attach(frame -> ws.sendText(frame, true));
        sender.send(dispatcher.readyFrame(runnerVersion()));
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
        String version = RunnerConnection.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? FALLBACK_VERSION : version;
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
            console.warn("Erreur de connexion : " + error.getMessage());
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
