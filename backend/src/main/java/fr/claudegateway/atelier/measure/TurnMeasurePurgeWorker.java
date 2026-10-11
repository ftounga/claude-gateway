package fr.claudegateway.atelier.measure;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Purge quotidienne des mesures de tour de plus de 180 jours (F-188 / SF-188-03). */
@Component
public class TurnMeasurePurgeWorker {

    private static final Logger log = LoggerFactory.getLogger(TurnMeasurePurgeWorker.class);

    private final TurnMeasureWriter writer;

    public TurnMeasurePurgeWorker(TurnMeasureWriter writer) {
        this.writer = writer;
    }

    @Scheduled(cron = "${app.atelier.turn-measures.purge-cron:0 40 3 * * *}", zone = "Europe/Paris")
    public void purge() {
        try {
            int removed = writer.purgeOlderThan(
                    Instant.now().minus(Duration.ofDays(TurnMeasureWriter.RETENTION_DAYS)));
            if (removed > 0) {
                log.info("Mesures de tour purgées (plus de {} jours) : {}",
                        TurnMeasureWriter.RETENTION_DAYS, removed);
            }
        } catch (RuntimeException e) {
            log.warn("Purge des mesures de tour en échec : {}", e.getMessage());
        }
    }
}
