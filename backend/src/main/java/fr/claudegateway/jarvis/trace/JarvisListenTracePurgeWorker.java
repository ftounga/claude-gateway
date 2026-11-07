package fr.claudegateway.jarvis.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Purge quotidienne du journal de l'écoute de plus de 7 jours (F-233 / SF-233-15). */
@Component
public class JarvisListenTracePurgeWorker {

    private static final Logger log = LoggerFactory.getLogger(JarvisListenTracePurgeWorker.class);

    private final JarvisListenTraceService service;

    public JarvisListenTracePurgeWorker(JarvisListenTraceService service) {
        this.service = service;
    }

    @Scheduled(cron = "${app.jarvis.listen-trace.purge-cron:0 45 3 * * *}", zone = "Europe/Paris")
    public void purge() {
        try {
            int removed = service.purge();
            if (removed > 0) {
                log.info("Journal de l'écoute de Jarvis purgé (plus de {} jours) : {}",
                        JarvisListenTraceService.RETENTION_DAYS, removed);
            }
        } catch (RuntimeException e) {
            log.warn("Purge du journal de l'écoute en échec : {}", e.getClass().getSimpleName());
        }
    }
}
