package fr.claudegateway.atelier.recall;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import fr.claudegateway.rag.provider.EmbeddingProvider;

/**
 * Le <b>backfill</b> des embeddings des messages existants (F-162 / SF-162-06). Sans lui, la recherche
 * sémantique ne verrait que les messages écrits après l'activation ; il rattrape le stock.
 *
 * <p><b>Borné et best-effort</b> : chaque passe traite au plus {@code max-per-run} messages, par lots de
 * {@code batch-size} embeddés en un appel groupé. Une défaillance de lot arrête la passe (rien n'est
 * perdu — la passe suivante reprendra) ; jamais d'exception propagée. <b>Éteint sans clé</b>.</p>
 *
 * <p><b>Isolation</b> : embeddre un message écrit sur SA propre ligne ne croise aucun tenant ; c'est la
 * <b>recherche</b> qui est filtrée {@code user_id} + {@code workspace_id}, pas l'indexation.</p>
 */
@Service
public class AtelierEmbeddingBackfillService {

    private static final Logger log = LoggerFactory.getLogger(AtelierEmbeddingBackfillService.class);

    private final EmbeddingProvider embeddingProvider;
    private final AtelierMessageEmbeddingStore store;
    private final RecallSemanticProperties properties;

    public AtelierEmbeddingBackfillService(
            @Qualifier("recallEmbeddingProvider") EmbeddingProvider embeddingProvider,
            AtelierMessageEmbeddingStore store, RecallSemanticProperties properties) {
        this.embeddingProvider = embeddingProvider;
        this.store = store;
        this.properties = properties;
    }

    /**
     * Embed les messages sans embedding, borné à {@code max-per-run} pour cette passe. Renvoie le nombre
     * de messages effectivement embeddés. Ne fait rien (0) si le sémantique n'est pas configuré.
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
            List<AtelierMessageEmbeddingStore.UnembeddedMessage> batch;
            try {
                batch = store.findUnembeddedBatch(want);
            } catch (RuntimeException ex) {
                log.debug("Backfill : lecture du lot en échec ({})", ex.getClass().getSimpleName());
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
            log.debug("Backfill embeddings : {} message(s) embeddé(s) ce cycle", done);
        }
        return done;
    }

    /** Embed un lot en un appel groupé, range chaque vecteur. Renvoie le nombre rangé (0 si échec). */
    private int embedBatch(List<AtelierMessageEmbeddingStore.UnembeddedMessage> batch) {
        List<String> texts = new ArrayList<>(batch.size());
        for (AtelierMessageEmbeddingStore.UnembeddedMessage message : batch) {
            texts.add(truncate(message.content()));
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
            log.debug("Backfill : embedding du lot en échec ({})", ex.getClass().getSimpleName());
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
