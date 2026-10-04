package fr.claudegateway.governance.map.index;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Stockage et recherche vectoriels des <b>faits de la carte</b> via pgvector (F-174 / SF-174-03), sur
 * le patron de {@code ResolutionMemoryEmbeddingStore} (F-148) : SQL natif sur la colonne
 * {@code host_map_facts.embedding vector(1536)}, jamais mappée en JPA.
 *
 * <p><b>PostgreSQL uniquement</b> : n'est sollicité que sur le chemin sémantique actif (clé présente,
 * donc la prod). <b>Isolation</b> : la recherche filtre toujours {@code user_id} ET {@code host_id}.</p>
 */
@Component
public class HostMapFactEmbeddingStore {

    private final JdbcTemplate jdbcTemplate;

    public HostMapFactEmbeddingStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Range l'embedding d'un fait. */
    public void store(UUID factId, float[] embedding) {
        if (factId == null || embedding == null || embedding.length == 0) {
            return;
        }
        jdbcTemplate.update("UPDATE host_map_facts SET embedding = CAST(? AS vector) WHERE id = ?",
                toVectorLiteral(embedding), factId);
    }

    /**
     * Les faits les plus proches d'une question, <b>sur la carte de CE poste</b>, du plus proche au plus
     * lointain, bornés à {@code topN}. La distance cosine est rendue pour que l'appelant applique son seuil.
     */
    public List<ScoredFact> searchSimilar(UUID userId, UUID hostId, float[] queryEmbedding, int topN) {
        if (userId == null || hostId == null || queryEmbedding == null || topN <= 0) {
            return List.of();
        }
        String literal = toVectorLiteral(queryEmbedding);
        String sql = "SELECT id, embedding <=> CAST(? AS vector) AS distance FROM host_map_facts "
                + "WHERE user_id = ? AND host_id = ? AND embedding IS NOT NULL "
                + "ORDER BY embedding <=> CAST(? AS vector) ASC LIMIT ?";
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new ScoredFact(rs.getObject("id", UUID.class), rs.getDouble("distance")),
                literal, userId, hostId, literal, topN);
    }

    /**
     * Un lot de faits sans embedding (balayage du travailleur, tous comptes : embedder un fait sur SA
     * propre ligne ne croise aucun tenant ; la recherche, elle, reste filtrée).
     */
    public List<UnembeddedFact> findUnembeddedBatch(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String sql = "SELECT id, heading, text FROM host_map_facts WHERE embedding IS NULL LIMIT ?";
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new UnembeddedFact(rs.getObject("id", UUID.class), rs.getString("heading"),
                        rs.getString("text")),
                limit);
    }

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

    /** Un fait candidat et sa distance cosine à la question (plus petit = plus proche). */
    public record ScoredFact(UUID id, double distance) {
    }

    /** Un fait à embedder. */
    public record UnembeddedFact(UUID id, String heading, String text) {
    }
}
