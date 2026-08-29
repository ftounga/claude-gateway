package fr.claudegateway.runner;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * File d'émission <b>mono-thread</b> du runner (F-38 / SF-38-04).
 *
 * <p>Raison d'être : {@code java.net.http.WebSocket} interdit un nouveau {@code sendText} tant que le
 * {@link CompletableFuture} du précédent n'est pas complété. Le heartbeat (thread ordonnanceur), les
 * résultats d'outils (threads workers) et les erreurs de protocole (thread de réception) émettent
 * tous depuis des threads différents : sans cette file, l'appel concurrent lève des
 * {@code IllegalStateException} intermittentes qui tuent la socket.</p>
 *
 * <p>Chaque envoi est donc exécuté sur l'unique thread {@code runner-sender}, qui <b>attend</b> la
 * complétion du futur précédent avant de rendre la main — la sérialisation est structurelle.</p>
 */
public final class FrameSender implements AutoCloseable {

    /** Au-delà, l'envoi est abandonné (socket bloquée) ; la reconnexion prendra le relais. */
    private static final long SEND_TIMEOUT_SECONDS = 30;

    private final Console console;
    private final ExecutorService executor;
    private volatile FrameTransport transport;

    public FrameSender(Console console) {
        this.console = console;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "runner-sender");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Branche la file sur la socket courante (à chaque (re)connexion). */
    public void attach(FrameTransport transport) {
        this.transport = transport;
    }

    /** Débranche la file : les trames en attente sont ignorées sans erreur. */
    public void detach() {
        this.transport = null;
    }

    /**
     * Met une trame en file. Ne bloque pas l'appelant et ne lève jamais : une émission impossible
     * (socket fermée, file arrêtée) est journalisée, pas propagée dans la boucle d'outils.
     */
    public void send(String frame) {
        try {
            executor.execute(() -> dispatch(frame));
        } catch (RejectedExecutionException e) {
            // File arrêtée (runner en cours d'arrêt) : rien à émettre.
        }
    }

    @Override
    public void close() {
        detach();
        executor.shutdownNow();
    }

    private void dispatch(String frame) {
        FrameTransport current = this.transport;
        if (current == null) {
            return; // socket absente : la trame est abandonnée (pas de rejeu, contrat §7)
        }
        try {
            current.send(frame).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            console.warn("Émission trop lente — trame abandonnée.");
        } catch (Exception e) {
            console.warn("Émission impossible : " + e.getMessage());
        }
    }
}
