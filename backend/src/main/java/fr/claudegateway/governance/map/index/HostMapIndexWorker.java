package fr.claudegateway.governance.map.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Déclencheur planifié de la tenue de l'index de la carte (F-174 / SF-174-02). Il ne fait que
 * déléguer à {@link HostMapIndexService#runOnce()} : l'extraction (appels au modèle) reste hors de tout
 * tour et de tout fil HTTP (règle des traitements lourds asynchrones).
 *
 * <p>Désactivable ({@code app.map-index.worker.enabled=false}, cas des tests). Le coupe-circuit
 * fonctionnel reste {@code APP_MAP_INDEX_ENABLED}.</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.map-index.worker", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class HostMapIndexWorker {

    private static final Logger log = LoggerFactory.getLogger(HostMapIndexWorker.class);

    private final HostMapIndexService service;

    public HostMapIndexWorker(HostMapIndexService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "${app.map-index.worker.initial-delay:PT2M}",
            fixedDelayString = "${app.map-index.worker.interval:PT60S}")
    public void run() {
        try {
            service.runOnce();
        } catch (RuntimeException ex) {
            log.warn("Tenue de l'index de la carte interrompue par une erreur inattendue");
        }
    }
}
