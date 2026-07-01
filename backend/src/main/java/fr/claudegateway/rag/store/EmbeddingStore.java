package fr.claudegateway.rag.store;

import java.util.UUID;

/**
 * Abstraction de la persistance vectorielle (F-06). Sépare le stockage de l'embedding (spécifique au
 * moteur : pgvector en Postgres) de la persistance JPA des métadonnées de chunk. Le domaine
 * ({@code IngestionService}) dépend de cette interface, jamais du SQL vectoriel directement.
 *
 * <p>Deux implémentations mutuellement exclusives, sélectionnées par {@code app.rag.vector-store} :
 * {@link PgVectorEmbeddingStore} (Postgres, SQL natif) et {@link NoopEmbeddingStore} (défaut, no-op —
 * dev/tests/H2, où la colonne vectorielle n'existe pas).</p>
 */
public interface EmbeddingStore {

    /**
     * Persiste le vecteur d'embedding d'un chunk déjà inséré (via son id).
     *
     * @param chunkId   identifiant du chunk cible (déjà persisté par {@code ChunkRepository})
     * @param embedding vecteur d'embedding (longueur = dimension du fournisseur)
     */
    void store(UUID chunkId, float[] embedding);
}
