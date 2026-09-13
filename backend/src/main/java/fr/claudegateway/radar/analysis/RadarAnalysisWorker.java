package fr.claudegateway.radar.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Le travailleur de l'analyse du Radar (F-101 / SF-101-01) : la file est vidée <b>en tâche de fond</b>,
 * hors du fil HTTP et portable fermé (cadrage §5, règle async de {@code CLAUDE.md}). Il ne porte aucune
 * logique : il délègue à {@link RadarAnalysisQueue}. Désactivable par
 * {@code app.radar.analysis.enabled=false} (cas des tests).
 */
@Component
@ConditionalOnProperty(name = "app.radar.analysis.enabled", havingValue = "true", matchIfMissing = true)
public class RadarAnalysisWorker {

    private static final Logger log = LoggerFactory.getLogger(RadarAnalysisWorker.class);

    private final RadarAnalysisQueue queue;

    public RadarAnalysisWorker(RadarAnalysisQueue queue) {
        this.queue = queue;
    }

    @Scheduled(fixedDelayString = "${app.radar.analysis.interval:15000}",
            initialDelayString = "${app.radar.analysis.initial-delay:30000}")
    public void analyze() {
        try {
            int processed = queue.runOnce();
            if (processed > 0) {
                log.debug("Radar : {} lot(s) traité(s)", processed);
            }
        } catch (RuntimeException ex) {
            // Le planificateur ne s'arrête jamais sur une erreur ponctuelle. Message neutre.
            log.warn("Radar : passage d'analyse interrompu ({})", ex.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${app.radar.analysis.expiry-interval:3600000}",
            initialDelayString = "${app.radar.analysis.initial-delay:30000}")
    public void expire() {
        try {
            int expired = queue.expireRaw();
            if (expired > 0) {
                log.info("Radar : {} lot(s) expiré(s) sans analyse, texte effacé", expired);
            }
        } catch (RuntimeException ex) {
            log.warn("Radar : expiration interrompue ({})", ex.getClass().getSimpleName());
        }
    }
}
