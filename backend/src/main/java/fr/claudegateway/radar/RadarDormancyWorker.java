package fr.claudegateway.radar;

import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lance chaque nuit le passage en sommeil des sujets silencieux (F-99 / SF-99-04). Traitement de
 * fond, hors du fil HTTP ; désactivable par {@code app.radar.dormancy.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.radar.dormancy.enabled", havingValue = "true", matchIfMissing = true)
public class RadarDormancyWorker {

    private static final Logger log = LoggerFactory.getLogger(RadarDormancyWorker.class);

    private final RadarDormancyService dormancy;

    public RadarDormancyWorker(RadarDormancyService dormancy) {
        this.dormancy = dormancy;
    }

    @Scheduled(cron = "${app.radar.dormancy.cron:0 30 3 * * *}")
    public void run() {
        int count = dormancy.sweep(OffsetDateTime.now());
        if (count > 0) {
            log.info("Radar : {} sujet(s) mis en sommeil", count);
        }
    }
}
