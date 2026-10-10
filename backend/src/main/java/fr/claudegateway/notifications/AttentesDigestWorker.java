package fr.claudegateway.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lance le récapitulatif des attentes chaque jour ouvré à 9 h, heure de Paris (F-185 / SF-185-07).
 * Les deux pods le lancent : le verrou {@code notification_digests} garantit une seule émission.
 * Désactivable par {@code app.notifications.digest.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.notifications.digest.enabled", havingValue = "true", matchIfMissing = true)
public class AttentesDigestWorker {

    private static final Logger log = LoggerFactory.getLogger(AttentesDigestWorker.class);

    private final AttentesDigestService digest;

    public AttentesDigestWorker(AttentesDigestService digest) {
        this.digest = digest;
    }

    @Scheduled(cron = "${app.notifications.digest.cron:0 0 9 * * MON-FRI}", zone = "Europe/Paris")
    public void run() {
        int sent = digest.run();
        if (sent > 0) {
            log.info("Récapitulatif des attentes : {} compte(s) prévenu(s)", sent);
        }
    }
}
