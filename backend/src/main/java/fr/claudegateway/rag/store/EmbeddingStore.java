package fr.claudegateway.rag.store;

import java.util.List;
import java.util.UUID;

/**
 * Abstraction de la persistance et de la recherche vectorielles (F-06 stockage, F-07 recherche).
 * Sépare le moteur vectoriel (pgvector en Postgres) de la persistance JPA des métadonnées de chunk.
 * Le domaine ({@code IngestionService}, {@code AskService}) dépend de cette interface, jamais du SQL
 * vectoriel directement (Provider Independence).
 *
 * <p>Deux implémentations mutuellement exclusives, sélectionnées par {@code app.rag.vector-store} :
 * {@link PgVectorEmbeddingStore} (Postgres, SQL natif) et {@link NoopEmbeddingStore} (défaut, no-op —
 * dev/tests/H2, où la colonne vectorielle n'existe pas ; la recherche y renvoie une liste vide).</p>
 */
public interface EmbeddingStore {

    /**
     * Persiste le vecteur d'embedding d'un chunk déjà inséré (via son id).
     *
     * @param chunkId   identifiant du chunk cible (déjà persisté par {@code ChunkRepository})
     * @param embedding vecteur d'embedding (longueur = dimension du fournisseur)
     */
    void store(UUID chunkId, float[] embedding);

    /**
     * Recherche les {@code topK} chunks les plus proches du vecteur requête, <b>isolés par
     * {@code user_id}</b> (jamais de chunk d'un autre utilisateur ne peut être retourné).
     *
     * @param userId          propriétaire des chunks (isolation multi-tenant, filtre obligatoire)
     * @param queryEmbedding  vecteur de la question (même dimension que les chunks indexés)
     * @param topK            nombre maximum de résultats (déjà borné par l'appelant)
     * @return les chunks candidats ordonnés du plus proche au plus lointain (vide si aucun / no-op)
     */
    List<ScoredChunk> search(UUID userId, float[] queryEmbedding, int topK);
}
