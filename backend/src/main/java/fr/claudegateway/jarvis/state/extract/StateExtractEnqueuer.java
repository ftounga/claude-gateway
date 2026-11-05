package fr.claudegateway.jarvis.state.extract;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import fr.claudegateway.jarvis.state.JarvisStateProperties;
import fr.claudegateway.shared.jobs.AsyncJobQueue;

import jakarta.annotation.PreDestroy;

/**
 * <b>La remise d'un tour à l'extraction</b> (F-247 / SF-247-04, porte Z1) : à la fin d'un tour, la réponse persistée
 * est confiée à un <b>fil de remise</b> qui insère le travail {@code STATE_EXTRACT} dans la file durable. Le tour ne
 * paie qu'une soumission en mémoire (≪ 1 ms) : ni requête, ni appel modèle dans son chemin. File de remise bornée
 * (1 000) : au-delà, le travail est jeté et compté, jamais attendu.
 */
@Component
public class StateExtractEnqueuer {

    private static final Logger log = LoggerFactory.getLogger(StateExtractEnqueuer.class);
    static final int MAX_HANDOFF = 1_000;

    private final AsyncJobQueue queue;
    private final JarvisStateProperties properties;
    private final ExecutorService handoff;
    private final AtomicLong rejected = new AtomicLong();

    public StateExtractEnqueuer(AsyncJobQueue queue, JarvisStateProperties properties) {
        this.queue = queue;
        this.properties = properties;
        this.handoff = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(MAX_HANDOFF),
                r -> {
                    Thread t = new Thread(r, "state-extract-handoff");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }, (r, ex) -> rejected.incrementAndGet());
    }

    /** Confie la réponse d'un tour à l'extraction. Ne lève jamais, ne bloque jamais. */
    public void afterTurn(UUID userId, UUID workspaceId, UUID messageId) {
        if (!properties.extract() || userId == null || workspaceId == null || messageId == null) {
            return;
        }
        try {
            handoff.execute(() -> queue.enqueue(AsyncJobQueue.Request.of(userId, StateExtractJobHandler.KIND,
                    StateExtractJobHandler.payload(messageId, workspaceId))));
        } catch (RuntimeException e) {
            log.debug("Extraction d'état : remise impossible ({})", e.getClass().getSimpleName());
        }
    }

    /** Les remises jetées faute de place (mesure). */
    public long rejected() {
        return rejected.get();
    }

    @PreDestroy
    void stop() {
        handoff.shutdown();
    }
}
