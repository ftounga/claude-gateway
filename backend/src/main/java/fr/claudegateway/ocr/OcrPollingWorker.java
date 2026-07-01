package fr.claudegateway.ocr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker de polling OCR asynchrone (F-05 / SF-05-02). Déclencheur planifié <b>intra-backend</b>
 * (décision par défaut OQ-10, réversible vers workers dédiés + file en V2) : il n'exécute aucune
 * logique métier lui-même, il délègue à {@link DocumentService#pollPendingJobs()} — les traitements
 * lourds restent hors du thread HTTP.
 *
 * <p>Désactivable par configuration ({@code app.ocr.polling.enabled=false}, cas des tests) pour un
 * déterminisme total.</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.ocr.polling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OcrPollingWorker {

    private static final Logger log = LoggerFactory.getLogger(OcrPollingWorker.class);

    private final DocumentService documentService;

    public OcrPollingWorker(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * Interroge périodiquement les jobs OCR asynchrones en attente. L'intervalle est configurable
     * ({@code app.ocr.polling.interval}, défaut 15 s). Toute exception non prévue est capturée pour
     * ne jamais interrompre le planificateur.
     */
    @Scheduled(fixedDelayString = "${app.ocr.polling.interval:15000}")
    public void poll() {
        try {
            int completed = documentService.pollPendingJobs();
            if (completed > 0) {
                log.debug("Polling OCR : {} document(s) finalisé(s) ce cycle", completed);
            }
        } catch (RuntimeException ex) {
            // Le planificateur ne doit jamais s'arrêter sur une erreur ponctuelle. Message neutre.
            log.warn("Cycle de polling OCR interrompu par une erreur inattendue");
        }
    }
}
