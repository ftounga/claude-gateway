package fr.claudegateway.rag.store;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stockage vectoriel no-op par défaut (dev, tests, H2 — où la colonne {@code chunks.embedding} de type
 * {@code vector} n'existe pas). Les métadonnées de chunk sont bien persistées par JPA ; seul le vecteur
 * n'est pas stocké. Actif tant que {@code app.rag.vector-store} n'est pas {@code pgvector}.
 *
 * <p>Permet au pipeline d'ingestion complet de s'exécuter et d'être testé sans Postgres/pgvector.</p>
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
}
