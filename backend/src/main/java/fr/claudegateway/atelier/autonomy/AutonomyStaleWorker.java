package fr.claudegateway.atelier.autonomy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * <b>Les courses orphelines</b> (F-219 / SF-219-02) : toutes les 5 minutes, une course {@code RUNNING} sans
 * signe de vie depuis {@code app.autonomy.stale-after} est close en échec et notifiée — un pod arrêté ne la
 * finira jamais. Désactivable ({@code app.autonomy.enabled=false}) ; désactivée en tests.
 */
@Component
@ConditionalOnProperty(name = "app.autonomy.enabled", havingValue = "true", matchIfMissing = true)
public class AutonomyStaleWorker {

    private final AutonomyService service;

    public AutonomyStaleWorker(AutonomyService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.autonomy.stale-interval:300000}",
            initialDelayString = "${app.autonomy.stale-initial-delay:120000}")
    public void tick() {
        service.closeStale();
    }
}
