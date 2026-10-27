package fr.claudegateway.atelier.autonomy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * <b>Les départs programmés depuis la conversation</b> (F-219 / SF-219-04) : toutes les 10 s, un départ dont
 * le sujet est libre part — le tour qui l'a programmé est fini. Désactivé en tests
 * ({@code app.autonomy.enabled=false}), où {@link AutonomyService#launchDeferred()} est appelé directement.
 */
@Component
@ConditionalOnProperty(name = "app.autonomy.enabled", havingValue = "true", matchIfMissing = true)
public class AutonomyDeferredWorker {

    private final AutonomyService service;

    public AutonomyDeferredWorker(AutonomyService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.autonomy.deferred-interval:10000}",
            initialDelayString = "${app.autonomy.deferred-initial-delay:30000}")
    public void tick() {
        service.launchDeferred();
    }
}
