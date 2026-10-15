package fr.claudegateway.atelier.routine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * <b>La tâche de fond des routines</b> (F-183 / SF-183-02) : toutes les 60 s, le planificateur examine
 * les routines programmées. Désactivable ({@code app.routines.enabled=false}) ; désactivée en tests, où
 * le planificateur est appelé directement.
 */
@Component
@ConditionalOnProperty(name = "app.routines.enabled", havingValue = "true", matchIfMissing = true)
public class RoutineWorker {

    private final RoutinePlanner planner;

    public RoutineWorker(RoutinePlanner planner) {
        this.planner = planner;
    }

    @Scheduled(fixedDelayString = "${app.routines.interval:60000}",
            initialDelayString = "${app.routines.initial-delay:50000}")
    public void tick() {
        planner.runOnce();
    }
}
