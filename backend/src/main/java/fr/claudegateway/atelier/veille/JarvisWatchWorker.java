package fr.claudegateway.atelier.veille;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>La tâche de fond des veilles</b> (F-230 / SF-230-02) : toutes les 60 s, le planificateur examine les veilles
 * actives ; chaque nuit, le journal des passages de plus de 90 jours est purgé. Désactivable
 * ({@code app.jarvis.watches.enabled=false}) ; désactivée en tests, où le planificateur est appelé directement.
 */
@Component
@ConditionalOnProperty(name = "app.jarvis.watches.enabled", havingValue = "true", matchIfMissing = true)
public class JarvisWatchWorker {

    /** Durée de conservation du journal des passages. */
    static final int RETENTION_DAYS = 90;

    private static final Logger log = LoggerFactory.getLogger(JarvisWatchWorker.class);

    private final JarvisWatchPlanner planner;
    private final JarvisWatchRunRepository runs;
    private final Clock clock;

    public JarvisWatchWorker(JarvisWatchPlanner planner, JarvisWatchRunRepository runs, Clock clock) {
        this.planner = planner;
        this.runs = runs;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.jarvis.watches.interval:60000}",
            initialDelayString = "${app.jarvis.watches.initial-delay:55000}")
    public void tick() {
        planner.runOnce();
    }

    @Scheduled(cron = "${app.jarvis.watches.purge-cron:0 40 3 * * *}")
    @Transactional
    public void purge() {
        int purged = runs.purgeBefore(OffsetDateTime.now(clock).minusDays(RETENTION_DAYS));
        if (purged > 0) {
            log.info("Veilles : {} passage(s) de plus de {} jours purgé(s)", purged, RETENTION_DAYS);
        }
    }
}
