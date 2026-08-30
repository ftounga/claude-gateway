package fr.claudegateway.runner.channel;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Canal d'émission de <b>repli</b> (F-38 / SF-38-09) : quand un proxy d'entreprise tue le WebSocket,
 * les trames sortantes ne partent plus sur une socket, elles <b>attendent dans cette file</b> qu'un
 * {@code POST /runner/poll} vienne les chercher.
 *
 * <p>Les trames sont celles du contrat de messages, <b>octet pour octet</b> : ce transport n'invente
 * aucun type de message, il déplace les mêmes enveloppes dans un corps HTTP.</p>
 *
 * <p>La file est <b>bornée</b> : un runner qui cesse de poller ne doit pas faire enfler la mémoire du
 * pod. Une file pleine fait échouer l'émission, donc l'appel devient {@code runner_unavailable} —
 * exactement comme une socket morte, et sans rejeu (contrat §7).</p>
 */
public final class LongPollingRunnerOutbound implements RunnerOutbound {

    /** Trames sortantes en attente au maximum ; au-delà, le runner est considéré parti. */
    static final int MAX_QUEUED_FRAMES = 256;

    /** Trames rendues au plus par un même poll (le reste part au poll suivant). */
    static final int MAX_FRAMES_PER_POLL = 64;

    /**
     * Sentinelle déposée à la fermeture : elle <b>réveille</b> un poll en attente au lieu de le
     * laisser courir jusqu'au bout de son délai. Jamais rendue au runner. Instance dédiée
     * (comparaison d'identité) : aucune trame du dispatcher ne peut la contrefaire.
     */
    @SuppressWarnings("StringOperationCanBeSimplified")
    private static final String CLOSE_SENTINEL = new String("runner-channel-closed");

    private final UUID workspaceId;
    private final UUID tokenId;
    private final UUID userId;
    private final Instant connectedAt = Instant.now();
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(MAX_QUEUED_FRAMES);
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Consumer<LongPollingRunnerOutbound> onClose;

    private volatile Instant lastPollAt = Instant.now();

    /**
     * @param onClose nettoyage exécuté <b>une seule fois</b> à la fermeture (retrait du registre et
     *                terminaison des appels en vol) — la fermeture peut venir du coupe-circuit, d'un
     *                {@code /runner/disconnect} ou du balayage d'inactivité
     */
    public LongPollingRunnerOutbound(UUID workspaceId, UUID userId, UUID tokenId,
            Consumer<LongPollingRunnerOutbound> onClose) {
        this.workspaceId = workspaceId;
        this.userId = userId;
        this.tokenId = tokenId;
        this.onClose = onClose;
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public UUID tokenId() {
        return tokenId;
    }

    public UUID userId() {
        return userId;
    }

    public Instant connectedAt() {
        return connectedAt;
    }

    @Override
    public void send(String frame) throws IOException {
        if (!open.get()) {
            throw new IOException("Canal long-polling ferme");
        }
        if (!queue.offer(frame)) {
            // Le runner ne vient plus chercher ses trames : inutile d'accumuler, l'appel echoue.
            throw new IOException("File de trames saturee");
        }
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        queue.offer(CLOSE_SENTINEL); // réveille un poll en attente
        if (onClose != null) {
            onClose.accept(this);
        }
    }

    /**
     * Retire les trames en attente, en bloquant au plus {@code wait} si la file est vide. Rend une
     * liste vide quand le délai expire sans rien à livrer : c'est le fonctionnement normal d'un
     * long-poll, pas une erreur.
     */
    public List<String> drain(Duration wait) throws InterruptedException {
        lastPollAt = Instant.now();
        List<String> frames = new ArrayList<>();
        String first = wait == null || wait.isZero() || wait.isNegative()
                ? queue.poll()
                : queue.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
        if (first == null) {
            return frames;
        }
        addUnlessSentinel(frames, first);
        while (frames.size() < MAX_FRAMES_PER_POLL) {
            String next = queue.poll();
            if (next == null) {
                break;
            }
            addUnlessSentinel(frames, next);
        }
        return frames;
    }

    /** Instant du dernier poll : c'est lui qui sert de preuve de vie (le poll est le heartbeat). */
    public Instant lastPollAt() {
        return lastPollAt;
    }

    private static void addUnlessSentinel(List<String> frames, String frame) {
        // Comparaison d'identité volontaire : la sentinelle est une instance unique.
        if (CLOSE_SENTINEL != frame) {
            frames.add(frame);
        }
    }
}
