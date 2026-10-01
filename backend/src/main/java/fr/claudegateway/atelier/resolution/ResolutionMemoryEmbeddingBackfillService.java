package fr.claudegateway.atelier.resolution;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.recall.RecallSemanticProperties;
import fr.claudegateway.atelier.resolution.ResolutionMemoryEmbeddingStore.UnembeddedResolution;
import fr.claudegateway.rag.provider.EmbeddingProvider;

/**
 * Le <b>backfill</b> des embeddings des résolutions déjà enregistrées (F-148 / SF-148-12), sur le patron de
 * {@code AtelierEmbeddingBackfillService} (SF-162-06). Sans lui, le rappel sémantique (SF-148-11) ne verrait
 * que les résolutions écrites après activation ; il rattrape le stock (≈293 lignes).
 *
 * <p><b>Borné et best-effort</b> : chaque passe traite au plus {@code max-per-run} résolutions, par lots de
 * {@code batch-size} embeddés en un appel groupé. Une défaillance de lot arrête la passe (rien n'est perdu —
 * la passe suivante reprendra) ; jamais d'exception propagée. <b>Éteint sans clé</b>.</p>
 *
 * <p><b>Isolation</b> : embeddre la <b>question</b> d'une résolution sur SA propre ligne ne croise aucun
 * tenant ; c'est la <b>recherche</b> (SF-148-10/11) qui est filtrée {@code (user_id, host_id)}, pas
 * l'indexation.</p>
 */
@Service
public class ResolutionMemoryEmbeddingBackfillService {

    private static final Logger log = LoggerFactory.getLogger(ResolutionMemoryEmbeddingBackfillService.class);

    private final EmbeddingProvider embeddingProvider;
    private final ResolutionMemoryEmbeddingStore store;
    private final RecallSemanticProperties properties;

    public ResolutionMemoryEmbeddingBackfillService(
            @Qualifier("recallEmbeddingProvider") EmbeddingProvider embeddingProvider,
            ResolutionMemoryEmbeddingStore store, RecallSemanticProperties properties) {
        this.embeddingProvider = embeddingProvider;
        this.store = store;
        this.properties = properties;
    }

    /**
     * Embed les résolutions sans embedding, borné à {@code max-per-run} pour cette passe. Renvoie le nombre
     * effectivement embeddé. Ne fait rien (0) si le sémantique n'est pas configuré.
     */
    public int runOnce() {
        if (!properties.isConfigured()) {
            return 0;
        }
        int batchSize = properties.backfill().batchSize();
        int maxPerRun = properties.backfill().maxPerRun();
        int done = 0;
        while (done < maxPerRun) {
            int want = Math.min(batchSize, maxPerRun - done);
            List<UnembeddedResolution> batch;
            try {
                batch = store.findUnembeddedBatch(want);
            } catch (RuntimeException ex) {
                log.debug("Backfill résolutions : lecture du lot en échec ({})", ex.getClass().getSimpleName());
                break;
            }
            if (batch.isEmpty()) {
                break; // Plus rien à embeddre.
            }
            int embedded = embedBatch(batch);
            done += embedded;
            if (embedded < batch.size()) {
                // Échec (partiel) : on s'arrête pour ne pas boucler sur le même lot ; reprise au prochain tick.
                break;
            }
        }
        if (done > 0) {
            log.debug("Backfill embeddings résolutions : {} résolution(s) embeddée(s) ce cycle", done);
        }
        return done;
    }

    /** Embed un lot (les questions) en un appel groupé, range chaque vecteur. Renvoie le nombre rangé (0 si échec). */
    private int embedBatch(List<UnembeddedResolution> batch) {
        List<String> texts = new ArrayList<>(batch.size());
        for (UnembeddedResolution resolution : batch) {
            texts.add(truncate(resolution.question()));
        }
        try {
            List<float[]> vectors = embeddingProvider.embed(texts);
            if (vectors.size() != batch.size()) {
                return 0;
            }
            int stored = 0;
            for (int i = 0; i < batch.size(); i++) {
                store.store(batch.get(i).id(), vectors.get(i));
                stored++;
            }
            return stored;
        } catch (RuntimeException ex) {
            log.debug("Backfill résolutions : embedding du lot en échec ({})", ex.getClass().getSimpleName());
            return 0;
        }
    }

    private static String truncate(String text) {
        String flat = text == null ? "" : text.strip();
        return flat.length() > RecallSemanticProperties.MAX_EMBED_CHARS
                ? flat.substring(0, RecallSemanticProperties.MAX_EMBED_CHARS)
                : flat;
    }
}
