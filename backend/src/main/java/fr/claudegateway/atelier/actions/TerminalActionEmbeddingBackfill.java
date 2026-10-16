package fr.claudegateway.atelier.actions;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * <b>Le rattrapage des embeddings d'attentes au démarrage</b> (F-180 / SF-180-01, décision D8).
 *
 * <p>En prod, 9 attentes n'avaient jamais reçu leur vecteur (nées avant la clé, ou fournisseur en
 * panne à l'inscription) : le dédoublonnage par le sens ne les voyait pas. Plutôt qu'une écriture
 * manuelle, une <b>tâche idempotente</b> livrée avec le code : au démarrage, sur l'exécuteur des
 * embeddings (jamais sur le fil de démarrage), les lignes sans vecteur sont vectorisées. Une ligne
 * déjà vectorisée n'est jamais relue ; un démarrage sans clé ne fait rien.</p>
 *
 * <p>Coupe-circuit : {@code app.atelier.actions.embedding-backfill.enabled=false}.</p>
 */
@Component
public class TerminalActionEmbeddingBackfill {

    private static final Logger log = LoggerFactory.getLogger(TerminalActionEmbeddingBackfill.class);

    static final int BATCH = 50;
    static final int MAX_PER_START = 500;

    private final TerminalActionSemanticDedup semantic;
    private final Executor executor;
    private final boolean enabled;

    public TerminalActionEmbeddingBackfill(TerminalActionSemanticDedup semantic,
            @Qualifier("atelierEmbeddingExecutor") Executor executor,
            @Value("${app.atelier.actions.embedding-backfill.enabled:true}") boolean enabled) {
        this.semantic = semantic;
        this.executor = executor;
        this.enabled = enabled;
    }

    /** Planifie le rattrapage quand l'application est prête. Jamais bloquant, jamais fatal. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled || !semantic.isEnabled()) {
            return;
        }
        try {
            executor.execute(this::run);
        } catch (RuntimeException ex) {
            log.debug("Rattrapage des embeddings d'attentes non planifié ({})", ex.getClass().getSimpleName());
        }
    }

    /** Le rattrapage lui-même ; rend le nombre de vecteurs rangés. */
    int run() {
        int stored = semantic.backfillAll(BATCH, MAX_PER_START);
        if (stored > 0) {
            log.info("Embeddings d'attentes rattrapés : {}", stored);
        }
        return stored;
    }
}
