package fr.claudegateway.radar.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Le travailleur du résumé du matin par courriel (F-110 / SF-110-04), hors du fil HTTP. Aucune logique : il délègue
 * à {@link RadarMorningMail}. Désactivable par {@code app.radar.morning-mail.enabled=false} (cas des tests).
 */
@Component
@ConditionalOnProperty(name = "app.radar.morning-mail.enabled", havingValue = "true", matchIfMissing = true)
public class RadarMorningMailWorker {

    private static final Logger log = LoggerFactory.getLogger(RadarMorningMailWorker.class);

    private final RadarMorningMail morningMail;

    public RadarMorningMailWorker(RadarMorningMail morningMail) {
        this.morningMail = morningMail;
    }

    @Scheduled(fixedDelayString = "${app.radar.morning-mail.interval:300000}",
            initialDelayString = "${app.radar.morning-mail.initial-delay:60000}")
    public void send() {
        try {
            int queued = morningMail.runOnce();
            if (queued > 0) {
                log.info("Résumé du matin : {} courriel(s) mis en file", queued);
            }
        } catch (RuntimeException ex) {
            log.warn("Résumé du matin : passage interrompu ({})", ex.getClass().getSimpleName());
        }
    }
}
