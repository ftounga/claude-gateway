package fr.claudegateway.jarvis.timing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Purge quotidienne des mesures de tour vocal de plus de 90 jours (F-233 / SF-233-01). */
@Component
public class JarvisLatencyPurgeWorker {

    private static final Logger log = LoggerFactory.getLogger(JarvisLatencyPurgeWorker.class);

    private final JarvisLatencyService service;

    public JarvisLatencyPurgeWorker(JarvisLatencyService service) {
        this.service = service;
    }

    @Scheduled(cron = "${app.jarvis.timings.purge-cron:0 50 3 * * *}", zone = "Europe/Paris")
    public void purge() {
        try {
            int removed = service.purge();
            if (removed > 0) {
                log.info("Mesures de tour vocal purgées (plus de {} jours) : {}",
                        JarvisLatencyService.RETENTION_DAYS, removed);
            }
        } catch (RuntimeException e) {
            log.warn("Purge des mesures de tour vocal en échec : {}", e.getClass().getSimpleName());
        }
    }
}
