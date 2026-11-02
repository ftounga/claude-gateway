package fr.claudegateway.jarvis.portrait;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Purge quotidienne du journal des allusions de plus de 60 jours (F-241 / SF-241-04, D11). */
@Component
public class JarvisAllusionPurgeWorker {

    private static final Logger log = LoggerFactory.getLogger(JarvisAllusionPurgeWorker.class);

    private final JarvisAllusionService service;

    public JarvisAllusionPurgeWorker(JarvisAllusionService service) {
        this.service = service;
    }

    @Scheduled(cron = "${app.jarvis.allusions.purge-cron:0 55 3 * * *}", zone = "Europe/Paris")
    public void purge() {
        try {
            int removed = service.purge();
            if (removed > 0) {
                log.info("Allusions de Jarvis purgées (plus de {} jours) : {}", JarvisAllusionService.RETENTION_DAYS,
                        removed);
            }
        } catch (RuntimeException e) {
            log.warn("Purge des allusions de Jarvis en échec : {}", e.getClass().getSimpleName());
        }
    }
}
