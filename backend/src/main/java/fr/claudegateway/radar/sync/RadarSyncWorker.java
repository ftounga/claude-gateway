package fr.claudegateway.radar.sync;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * <b>La tâche de fond</b> de la synchro du soir (F-100 / SF-100-02) : toutes les 60 s, le planificateur
 * examine les postes activés. Désactivable ({@code app.radar.sync.enabled=false}) ; désactivée en tests,
 * où le planificateur est appelé directement.
 */
@Component
@ConditionalOnProperty(name = "app.radar.sync.enabled", havingValue = "true", matchIfMissing = true)
public class RadarSyncWorker {

    private final RadarSyncPlanner planner;

    public RadarSyncWorker(RadarSyncPlanner planner) {
        this.planner = planner;
    }

    @Scheduled(fixedDelayString = "${app.radar.sync.interval:60000}",
            initialDelayString = "${app.radar.sync.initial-delay:45000}")
    public void tick() {
        planner.runOnce();
    }
}
