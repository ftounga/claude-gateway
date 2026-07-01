package fr.claudegateway.rag.store;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Stockage vectoriel pgvector (Postgres) — actif sur {@code app.rag.vector-store=pgvector} (cluster).
 * Met à jour la colonne {@code chunks.embedding vector(1536)} via SQL natif, en transtypant une
 * représentation littérale {@code [v1,v2,...]} vers le type {@code vector} de pgvector.
 *
 * <p>Confiné à cette implémentation : le domaine ne connaît que {@link EmbeddingStore}. Aucun contenu
 * ni secret n'est manipulé ici (uniquement l'id de chunk et le vecteur numérique).</p>
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
