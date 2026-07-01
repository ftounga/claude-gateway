package fr.claudegateway.rag.store;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stockage vectoriel no-op par défaut (dev, tests, H2 — où la colonne {@code chunks.embedding} de type
 * {@code vector} n'existe pas). Les métadonnées de chunk sont bien persistées par JPA ; seul le vecteur
 * n'est pas stocké et la recherche vectorielle renvoie toujours une liste vide. Actif tant que
 * {@code app.rag.vector-store} n'est pas {@code pgvector}.
 *
 * <p>Permet au pipeline d'ingestion complet et au Q&A (F-07, via le repli) de s'exécuter et d'être
 * testés sans Postgres/pgvector.</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.rag", name = "vector-store", havingValue = "noop", matchIfMissing = true)
public class NoopEmbeddingStore implements EmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(NoopEmbeddingStore.class);

    @Override
    public void store(UUID chunkId, float[] embedding) {
        // Aucun stockage vectoriel (pas de colonne vector hors Postgres). Aucun contenu/secret loggé.
        log.debug("EmbeddingStore no-op : vecteur du chunk {} non persisté (dimension={})",
                chunkId, embedding != null ? embedding.length : 0);
    }

    @Override
    public List<ScoredChunk> search(UUID userId, float[] queryEmbedding, int topK) {
        // Pas de moteur vectoriel hors Postgres : aucune recherche possible → repli côté AskService.
        return List.of();
    }
}
