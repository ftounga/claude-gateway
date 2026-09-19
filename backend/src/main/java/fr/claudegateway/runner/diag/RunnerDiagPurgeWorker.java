package fr.claudegateway.runner.diag;

import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purge chaque nuit les événements de diagnostic du runner de plus de 7 jours (F-132 / SF-132-02).
 * Traitement de fond, hors du fil HTTP ; désactivable par {@code app.runner.diag.purge.enabled=false}
 * (tests). Best-effort : une purge en échec ne réveille personne et ne casse rien.
 */
@Component
@ConditionalOnProperty(name = "app.runner.diag.purge.enabled", havingValue = "true", matchIfMissing = true)
public class RunnerDiagPurgeWorker {

    private static final Logger log = LoggerFactory.getLogger(RunnerDiagPurgeWorker.class);

    private final RunnerDiagService diagService;

    public RunnerDiagPurgeWorker(RunnerDiagService diagService) {
        this.diagService = diagService;
    }

    @Scheduled(cron = "${app.runner.diag.purge.cron:0 45 3 * * *}")
    public void run() {
        try {
            int purged = diagService.purgeExpired(OffsetDateTime.now());
            if (purged > 0) {
                log.info("Diagnostic runner : {} événement(s) purgé(s) (TTL {} j)", purged,
                        RunnerDiagService.TTL_DAYS);
            }
        } catch (RuntimeException e) {
            log.warn("Purge du diagnostic runner en échec : {}", e.getMessage());
        }
    }
}
