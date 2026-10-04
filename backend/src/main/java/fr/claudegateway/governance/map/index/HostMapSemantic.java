package fr.claudegateway.governance.map.index;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.recall.RecallSemanticProperties;
import fr.claudegateway.governance.map.index.HostMapFactEmbeddingStore.ScoredFact;
import fr.claudegateway.governance.map.index.HostMapFactEmbeddingStore.UnembeddedFact;
import fr.claudegateway.rag.provider.EmbeddingProvider;

/**
 * <b>Le maillon sémantique de la recherche</b> (F-174 / SF-174-03, D5).
 *
 * <p>Même fournisseur d'embeddings que le rappel de conversation (F-162, ADR-011), par l'interface
 * abstraite {@link EmbeddingProvider} — jamais un SDK en direct. <b>Éteint sans clé</b>
 * ({@link RecallSemanticProperties#isConfigured()}) : la recherche se passe alors de ce maillon.</p>
 *
 * <p><b>Ne lève jamais</b> : une défaillance rend une liste vide, et la recherche continue avec ses
 * autres maillons.</p>
 */
@Service
public class HostMapSemantic {

    private static final Logger log = LoggerFactory.getLogger(HostMapSemantic.class);

    /** Taille d'un lot envoyé au fournisseur. */
    static final int BATCH = 50;

    private final EmbeddingProvider embeddingProvider;
    private final HostMapFactEmbeddingStore store;
    private final RecallSemanticProperties semanticProperties;

    public HostMapSemantic(@Qualifier("recallEmbeddingProvider") EmbeddingProvider embeddingProvider,
            HostMapFactEmbeddingStore store, RecallSemanticProperties semanticProperties) {
        this.embeddingProvider = embeddingProvider;
        this.store = store;
        this.semanticProperties = semanticProperties;
    }

    public boolean isEnabled() {
        return semanticProperties.isConfigured();
    }

    /** Les faits les plus proches de la question, sur la carte de CE poste. Vide si éteint ou en panne. */
    public List<ScoredFact> nearest(UUID userId, UUID hostId, String question, int topN) {
        if (!isEnabled() || question == null || question.isBlank()) {
            return List.of();
        }
        try {
            List<float[]> vectors = embeddingProvider.embed(List.of(truncate(question)));
            if (vectors.isEmpty()) {
                return List.of();
            }
            return store.searchSimilar(userId, hostId, vectors.get(0), topN);
        } catch (RuntimeException ex) {
            log.debug("Carte : maillon sémantique indisponible ({})", ex.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * Embedde un lot de faits qui n'en ont pas encore (appelé par le travailleur de l'index).
     *
     * @return le nombre de faits embeddés
     */
    public int embedPending(int max) {
        if (!isEnabled() || max <= 0) {
            return 0;
        }
        int done = 0;
        try {
            while (done < max) {
                List<UnembeddedFact> batch = store.findUnembeddedBatch(Math.min(BATCH, max - done));
                if (batch.isEmpty()) {
                    break;
                }
                List<String> texts = new ArrayList<>();
                for (UnembeddedFact fact : batch) {
                    // Le titre de section donne au fait son contexte : « Jetons — périme le 2026-10-09 ».
                    String heading = fact.heading() == null ? "" : fact.heading().strip() + " — ";
                    texts.add(truncate(heading + fact.text()));
                }
                List<float[]> vectors = embeddingProvider.embed(texts);
                for (int i = 0; i < batch.size() && i < vectors.size(); i++) {
                    store.store(batch.get(i).id(), vectors.get(i));
                }
                done += batch.size();
                if (vectors.size() < batch.size()) {
                    break; // Réponse incomplète : on reprendra à la passe suivante.
                }
            }
        } catch (RuntimeException ex) {
            log.debug("Carte : embeddings des faits non calculés ({})", ex.getClass().getSimpleName());
        }
        return done;
    }

    private static String truncate(String text) {
        String flat = text.strip();
        return flat.length() > RecallSemanticProperties.MAX_EMBED_CHARS
                ? flat.substring(0, RecallSemanticProperties.MAX_EMBED_CHARS)
                : flat;
    }
}
