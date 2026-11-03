package fr.claudegateway.jarvis.mail;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * <b>Le planificateur de la relève</b> (F-242 / SF-242-02, D4) — toutes les 60 s il relève les boîtes <b>dues</b>
 * (chacune toutes les 5 min) ; la purge à 30 jours tourne chaque nuit. Coupe-circuit {@code APP_JARVIS_MAIL_ENABLED} ;
 * éteint en tests, où {@link JarvisMailPoller#runOnce()} est appelé directement.
 */
@Component
@ConditionalOnProperty(name = "app.jarvis.mail.enabled", havingValue = "true", matchIfMissing = true)
public class JarvisMailPollWorker {

    private static final Logger log = LoggerFactory.getLogger(JarvisMailPollWorker.class);

    static final int RETENTION_DAYS = 30;

    private final JarvisMailPoller poller;
    private final JarvisMailItemRepository items;
    private final JarvisMailTriageService triage;
    private final Clock clock;

    public JarvisMailPollWorker(JarvisMailPoller poller, JarvisMailItemRepository items,
            JarvisMailTriageService triage, Clock clock) {
        this.poller = poller;
        this.items = items;
        this.triage = triage;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.jarvis.mail.tick:60000}", initialDelayString = "${app.jarvis.mail.initial-delay:65000}")
    public void tick() {
        try {
            poller.runOnce();
            // SF-242-03 — rattrapage : les courriers restés « en attente » (panne, redémarrage) sont triés.
            triage.triagePending();
        } catch (RuntimeException e) {
            log.warn("Courrier : passage de relève interrompu ({})", e.getClass().getSimpleName());
        }
    }

    @Scheduled(cron = "${app.jarvis.mail.purge-cron:0 50 3 * * *}")
    public void purge() {
        int purged = items.purgeBefore(OffsetDateTime.now(clock).minusDays(RETENTION_DAYS));
        if (purged > 0) {
            log.info("Courrier : {} courrier(s) de plus de {} jours purgé(s)", purged, RETENTION_DAYS);
        }
    }
}
