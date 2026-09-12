package fr.claudegateway.runner.relay;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.TurnEvent;
import fr.claudegateway.atelier.live.TurnSubscriber;

/**
 * Le spectateur qu'un pod <b>pair</b> branche sur un tour local (F-84 / SF-84-02) : il écrit les
 * événements en NDJSON dans la réponse du relais.
 *
 * <p>Deux threads écrivent : celui qui exécute le tour (les événements, via
 * {@link #deliver}) et celui de la requête (les {@code ping}). Ils sont sérialisés par un verrou
 * propre à la réponse — sans quoi une ligne NDJSON pourrait être coupée en deux et le cadrage du
 * flux serait perdu, exactement comme dans {@code RunnerRelayController}.</p>
 */
final class NdjsonTurnSubscriber implements TurnSubscriber {

    /** Battement écrit en l'absence d'événement, pour que le délai de lecture du pair ne coupe pas. */
    private static final long PING_INTERVAL_MS = 20_000L;

    private final OutputStream output;
    private final ObjectMapper objectMapper;
    private final Object lock = new Object();
    private final CountDownLatch done = new CountDownLatch(1);

    NdjsonTurnSubscriber(OutputStream output, ObjectMapper objectMapper) {
        this.output = output;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean deliver(TurnEvent event) {
        if (!write(TurnRelayNdjson.eventLine(objectMapper, event))) {
            // Le pod spectateur est parti : on se détache, et le tour continue — c'est toute la
            // règle de F-84, appliquée au relais comme au navigateur.
            done.countDown();
            return false;
        }
        return true;
    }

    @Override
    public void finish() {
        write(TurnRelayNdjson.endLine(objectMapper));
        done.countDown();
    }

    /** Dit au pair si ce pod détient bien le tour. Toujours la première ligne. */
    boolean writeAttached(boolean attached, String turnId, long cursor, long startedAtMs) {
        return write(TurnRelayNdjson.attachedLine(objectMapper, attached, turnId, cursor,
                startedAtMs));
    }

    /**
     * Tient la réponse ouverte jusqu'à la fin du tour, en battant régulièrement. Rend la main dès que
     * le tour est fini, que le spectateur est parti, ou que le garde-fou de durée est atteint.
     */
    void awaitFinish(LiveTurn turn, long maxDurationMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxDurationMs;
        while (turn.live() && System.currentTimeMillis() < deadline) {
            if (done.await(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                return;
            }
            if (!write(TurnRelayNdjson.pingLine(objectMapper))) {
                return;
            }
        }
    }

    /** Écrit une ligne ; {@code false} dit que le pair est parti — jamais une exception qui remonte. */
    private boolean write(String line) {
        synchronized (lock) {
            try {
                output.write(line.getBytes(StandardCharsets.UTF_8));
                output.write('\n');
                output.flush();
                return true;
            } catch (IOException | RuntimeException ex) {
                return false;
            }
        }
    }
}
