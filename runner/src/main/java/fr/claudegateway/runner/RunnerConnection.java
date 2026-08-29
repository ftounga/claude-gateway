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
 * périodique, reconnexion à backoff plafonné et arrêt propre.
 *
 * <p>La socket est authentifiée par le jeton runner porté en query param (SF-38-02). Un rejet de
 * handshake {@code 401} lève {@link AuthRejectedException} pour que l'appelant efface le jeton
 * périmé. {@link #stop()} (déclenché par {@code Ctrl-C}) ferme la socket (close 1000) et libère la
 * boucle.</p>
 *
 * <p>Note : cette classe est intrinsèquement I/O réseau ; elle n'est pas couverte par les tests
 * unitaires (la connexion réelle est un smoke manuel). La logique testable (backoff, config, proxy,
 * jeton) est isolée dans des classes dédiées.</p>
 */
public final class RunnerConnection {

    private static final String HEARTBEAT_MESSAGE = "{\"type\":\"heartbeat\"}";

    private final HttpClient httpClient;
    private final RunnerConfig config;
    private final Console console;
    private final Backoff backoff;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile WebSocket webSocket;
    private volatile CountDownLatch closedLatch;
    private ScheduledExecutorService heartbeatExecutor;

    public RunnerConnection(HttpClient httpClient, RunnerConfig config, Console console) {
        this.httpClient = httpClient;
        this.config = config;
        this.console = console;
        this.backoff = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(30));
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
        URI uri = config.webSocketUri(token);
        console.info("Cible WebSocket : " + safeUri(uri));

        while (running.get()) {
            CountDownLatch latch = new CountDownLatch(1);
            closedLatch = latch;
            try {
                connectOnce(uri, latch);
                backoff.reset();
                latch.await(); // attend la fermeture/erreur de la socket
            } catch (AuthRejectedException e) {
                shutdownHeartbeat();
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                console.warn("Connexion échouée : " + e.getMessage());
            }
            if (!running.get()) {
                break;
            }
            Duration delay = backoff.nextDelay();
            console.warn("Reconnexion dans " + delay.toSeconds() + " s…");
            if (!sleep(delay)) {
                break;
            }
        }
        shutdownHeartbeat();
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
        try {
            this.webSocket = future.join();
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof WebSocketHandshakeException handshake
                    && handshake.getResponse().statusCode() == 401) {
                throw new AuthRejectedException("Jeton refusé par la gateway (401)");
            }
            throw new RunnerException(cause.getMessage() == null ? cause.toString() : cause.getMessage(), cause);
        }
        console.info("Runner connecté.");
        startHeartbeat();
    }

    private void startHeartbeat() {
        ScheduledFuture<?> ignored = heartbeatExecutor.scheduleAtFixedRate(() -> {
            WebSocket ws = this.webSocket;
            if (ws != null && running.get()) {
                ws.sendText(HEARTBEAT_MESSAGE, true);
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

    /** Listener WebSocket : journalise les acks de heartbeat et signale la fermeture. */
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
                if (payload.contains("heartbeat_ack")) {
                    console.info("Heartbeat confirmé (ack).");
                } else {
                    console.info("Message reçu : " + payload);
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            console.warn("Connexion fermée par la gateway (" + statusCode + " " + reason + ").");
            latch.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            console.warn("Erreur de connexion : " + error.getMessage());
            latch.countDown();
        }
    }

    /** Handshake refusé (jeton périmé/révoqué) : l'appelant doit effacer le jeton. */
    public static final class AuthRejectedException extends RuntimeException {
        public AuthRejectedException(String message) {
            super(message);
        }
    }
}
