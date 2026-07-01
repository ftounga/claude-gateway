package fr.claudegateway.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker d'auto-indexation RAG (F-06 / SF-06-02). Déclencheur planifié <b>intra-backend</b>
 * (décision par défaut OQ-10, réversible vers workers dédiés + file en V2), aligné sur
 * {@code OcrPollingWorker} (F-05) : il n'exécute aucune logique métier lui-même, il délègue à
 * {@link IngestionService#ingestPending()} — les traitements lourds (chunking + embeddings) restent
 * hors du thread HTTP.
 *
 * <p>Désactivable par configuration ({@code app.rag.ingestion.enabled=false}, cas des tests) pour un
 * déterminisme total.</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.rag.ingestion", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);

    private final IngestionService ingestionService;

    public IngestionWorker(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * Indexe périodiquement les documents {@code EXTRACTED} en attente. L'intervalle est configurable
     * ({@code app.rag.ingestion.interval}, défaut 20 s). Toute exception non prévue est capturée pour
     * ne jamais interrompre le planificateur.
     */
    @Scheduled(fixedDelayString = "${app.rag.ingestion.interval:20000}")
    public void run() {
        try {
            int indexed = ingestionService.ingestPending();
            if (indexed > 0) {
                log.debug("Ingestion RAG : {} document(s) indexé(s) ce cycle", indexed);
            }
        } catch (RuntimeException ex) {
            // Le planificateur ne doit jamais s'arrêter sur une erreur ponctuelle. Message neutre.
            log.warn("Cycle d'ingestion RAG interrompu par une erreur inattendue");
        }
    }
}
