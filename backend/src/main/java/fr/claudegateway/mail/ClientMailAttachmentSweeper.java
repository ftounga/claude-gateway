package fr.claudegateway.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Balaye chaque nuit les pièces jointes orphelines des courriels du client (F-110 / SF-110-05) : celles écrites
 * dans le stockage mais jamais rattachées à un envoi (transaction annulée), et celles restées après un état final.
 * Traitement de fond, hors du fil HTTP (règle async de {@code CLAUDE.md}). Aucune logique : il délègue à
 * {@link ClientMailOutbox#sweepOrphans()}. Désactivable par {@code app.client-mail.sweeper.enabled=false} (tests).
 */
@Component
@ConditionalOnProperty(name = "app.client-mail.sweeper.enabled", havingValue = "true", matchIfMissing = true)
public class ClientMailAttachmentSweeper {

    private static final Logger log = LoggerFactory.getLogger(ClientMailAttachmentSweeper.class);

    private final ClientMailOutbox outbox;

    public ClientMailAttachmentSweeper(ClientMailOutbox outbox) {
        this.outbox = outbox;
    }

    @Scheduled(cron = "${app.client-mail.sweeper.cron:0 45 3 * * *}")
    public void sweep() {
        try {
            outbox.sweepOrphans();
        } catch (RuntimeException ex) {
            // Le planificateur ne s'arrête jamais sur une erreur ponctuelle. Message neutre.
            log.warn("Courriels du client : balayage des pièces orphelines interrompu ({})",
                    ex.getClass().getSimpleName());
        }
    }
}
