package fr.claudegateway.jarvis.state.capture;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.deposit.DepositReceivedListener;
import fr.claudegateway.jarvis.state.JarvisStateProperties;
import fr.claudegateway.shared.jobs.AsyncJobQueue;

import jakarta.annotation.PreDestroy;

/**
 * <b>La remise d'une capture à la lecture</b> (F-247 / SF-247-12, porte Z1b) : à la réception d'un dépôt d'image
 * (png, jpeg, webp ; 5 Mo au plus) dans un sujet rattaché à un client, l'image est confiée à un <b>fil de remise</b>
 * qui insère le travail {@code CAPTURE_READ} dans la file durable, l'image dans {@code payload_bytes}. Le dépôt ne
 * paie qu'une copie et une soumission en mémoire ; sa réponse part sans attendre. Si le pod tombe entre la réponse et
 * l'insertion, la capture est perdue, et c'est compté ({@link #rejected()}).
 */
@Component
public class CaptureReadEnqueuer implements DepositReceivedListener {

    private static final Logger log = LoggerFactory.getLogger(CaptureReadEnqueuer.class);
    static final int MAX_HANDOFF = 50;
    static final int MAX_BYTES = 5 * 1024 * 1024;

    private final AsyncJobQueue queue;
    private final JarvisStateProperties properties;
    private final ExecutorService handoff;
    private final AtomicLong rejected = new AtomicLong();

    public CaptureReadEnqueuer(AsyncJobQueue queue, JarvisStateProperties properties) {
        this.queue = queue;
        this.properties = properties;
        this.handoff = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(MAX_HANDOFF),
                r -> {
                    Thread t = new Thread(r, "capture-read-handoff");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }, (r, ex) -> rejected.incrementAndGet());
    }

    @Override
    public void received(Workspace workspace, UUID depositId, String contentType, byte[] bytes, Instant receivedAt) {
        if (!properties.captures() || workspace == null || workspace.getHostId() == null || depositId == null
                || bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            return;
        }
        String mediaType = mediaType(contentType, bytes);
        if (mediaType == null) {
            return;
        }
        byte[] copy = bytes.clone();
        String payload = CaptureReadJobHandler.payload(depositId, workspace.getId(), workspace.getHostId(), receivedAt,
                mediaType);
        UUID userId = workspace.getUserId();
        try {
            handoff.execute(() -> queue.enqueue(new AsyncJobQueue.Request(userId, CaptureReadJobHandler.KIND, 100,
                    payload, copy, 2)));
        } catch (RuntimeException e) {
            log.debug("Captures : remise impossible ({})", e.getClass().getSimpleName());
        }
    }

    /**
     * F-247 / SF-247-16 — confie à la lecture une capture <b>déjà déposée</b> (relue sur le poste par « Synchroniser »)
     * : mise en file <b>directe</b> (on est déjà dans un fil de la file durable), priorité basse, {@code depositedAt} =
     * l'heure du dépôt d'origine, marquée de la synchronisation. Mêmes bornes que la remise à la réception.
     *
     * @return vrai si la lecture est en file
     */
    public boolean enqueueExisting(Workspace workspace, UUID depositId, byte[] bytes, Instant depositedAt,
            int priority, UUID syncId) {
        if (!properties.captures() || workspace == null || workspace.getHostId() == null || depositId == null
                || bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES || depositedAt == null) {
            return false;
        }
        String mediaType = mediaType(null, bytes);
        if (mediaType == null) {
            return false;
        }
        String payload = CaptureReadJobHandler.payload(depositId, workspace.getId(), workspace.getHostId(),
                depositedAt, mediaType, syncId);
        return queue.enqueue(new AsyncJobQueue.Request(workspace.getUserId(), CaptureReadJobHandler.KIND, priority,
                payload, bytes, 2)).isPresent();
    }

    /** Le type d'image retenu (png, jpeg, webp), d'après les octets d'abord ; {@code null} sinon. */
    static String mediaType(String contentType, byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E'
                && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        // Le type annoncé par le client ne suffit jamais : seuls les octets d'une image reconnue sont lus.
        return null;
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
