package fr.claudegateway.rag.store;

import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Stockage et recherche vectoriels pgvector (Postgres) — actif sur {@code app.rag.vector-store=pgvector}
 * (cluster). Met à jour la colonne {@code chunks.embedding vector(1536)} via SQL natif et exécute la
 * recherche des plus proches voisins (distance L2 {@code <->}, cohérente avec l'index ivfflat de 011).
 *
 * <p>Confiné à cette implémentation : le domaine ne connaît que {@link EmbeddingStore}. Aucun contenu
 * ni secret n'est manipulé ici (uniquement l'id de chunk et le vecteur numérique). La recherche filtre
 * toujours sur {@code user_id} (isolation multi-tenant).</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.rag", name = "vector-store", havingValue = "pgvector")
public class PgVectorEmbeddingStore implements EmbeddingStore {

    private final JdbcTemplate jdbcTemplate;

    public PgVectorEmbeddingStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void store(UUID chunkId, float[] embedding) {
        if (embedding == null) {
            return;
        }
        // Le cast texte -> vector est l'API officielle pgvector pour l'écriture paramétrée.
        jdbcTemplate.update(
                "UPDATE chunks SET embedding = CAST(? AS vector) WHERE id = ?",
                toVectorLiteral(embedding), chunkId);
    }

    @Override
    public List<ScoredChunk> search(UUID userId, float[] queryEmbedding, int topK) {
        if (queryEmbedding == null || topK <= 0) {
            return List.of();
        }
        // Recherche des plus proches voisins isolée user_id. L'opérateur <-> (L2) exploite l'index
        // ivfflat (vector_l2_ops) de la migration 011. Le vecteur ne sert que dans SELECT (alias
        // réutilisé par ORDER BY) : un seul paramètre vecteur.
        String sql = "SELECT id, embedding <-> CAST(? AS vector) AS distance "
                + "FROM chunks WHERE user_id = ? AND embedding IS NOT NULL "
                + "ORDER BY distance ASC LIMIT ?";
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ScoredChunk(
                        rs.getObject("id", UUID.class), rs.getDouble("distance")),
                toVectorLiteral(queryEmbedding), userId, topK);
    }

    /** Sérialise un vecteur au format littéral pgvector : {@code [0.1,0.2,...]}. */
    private static String toVectorLiteral(float[] embedding) {
        StringBuilder builder = new StringBuilder(embedding.length * 8 + 2);
        builder.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(embedding[i]);
        }
        return builder.append(']').toString();
    }
}
