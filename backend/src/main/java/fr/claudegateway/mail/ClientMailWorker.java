package fr.claudegateway.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Le travailleur de la file des courriels du client (F-110 / SF-110-02) : hors du fil HTTP et hors du tour
 * (règle async de {@code CLAUDE.md}). Aucune logique : il délègue à {@link ClientMailOutbox}. Désactivable par
 * {@code app.client-mail.worker.enabled=false} (cas des tests).
 */
@Component
@ConditionalOnProperty(name = "app.client-mail.worker.enabled", havingValue = "true", matchIfMissing = true)
public class ClientMailWorker {

    private static final Logger log = LoggerFactory.getLogger(ClientMailWorker.class);

    private final ClientMailOutbox outbox;

    public ClientMailWorker(ClientMailOutbox outbox) {
        this.outbox = outbox;
    }

    @Scheduled(fixedDelayString = "${app.client-mail.worker.interval:5000}",
            initialDelayString = "${app.client-mail.worker.initial-delay:20000}")
    public void send() {
        try {
            outbox.runOnce();
        } catch (RuntimeException ex) {
            // Le planificateur ne s'arrête jamais sur une erreur ponctuelle. Message neutre.
            log.warn("Courriels du client : passage interrompu ({})", ex.getClass().getSimpleName());
        }
    }
}
