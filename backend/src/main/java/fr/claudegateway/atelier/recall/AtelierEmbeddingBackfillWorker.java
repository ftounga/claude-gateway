package fr.claudegateway.atelier.recall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Déclencheur planifié du backfill des embeddings de messages (F-162 / SF-162-06), sur le patron de
 * {@code IngestionWorker} (F-06) et {@code OcrPollingWorker} (F-05) : il n'exécute aucune logique
 * lui-même, il délègue à {@link AtelierEmbeddingBackfillService#runOnce()} — le calcul lourd
 * (embeddings) reste hors du thread HTTP.
 *
 * <p>Désactivable par configuration ({@code app.atelier.recall.semantic.backfill.enabled=false}, cas des
 * tests) pour un déterminisme total. Sans clé, {@code runOnce()} ne fait de toute façon rien.</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.atelier.recall.semantic.backfill", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AtelierEmbeddingBackfillWorker {

    private static final Logger log = LoggerFactory.getLogger(AtelierEmbeddingBackfillWorker.class);

    private final AtelierEmbeddingBackfillService backfillService;

    public AtelierEmbeddingBackfillWorker(AtelierEmbeddingBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    /**
     * Draine périodiquement les messages sans embedding, par passes bornées. L'intervalle est configurable
     * ({@code app.atelier.recall.semantic.backfill.interval}, défaut 30 s). Toute exception est capturée
     * pour ne jamais interrompre le planificateur.
     */
    @Scheduled(fixedDelayString = "${app.atelier.recall.semantic.backfill.interval:PT30S}")
    public void run() {
        try {
            backfillService.runOnce();
        } catch (RuntimeException ex) {
            // Le planificateur ne doit jamais s'arrêter sur une erreur ponctuelle. Message neutre.
            log.warn("Cycle de backfill d'embeddings interrompu par une erreur inattendue");
        }
    }
}
