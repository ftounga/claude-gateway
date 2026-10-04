package fr.claudegateway.atelier.actions;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Stockage et recherche vectoriels des <b>descriptions d'attentes</b> via <b>pgvector</b>
 * (F-175 / SF-175-03), sur le patron de {@code ResolutionMemoryEmbeddingStore}. Écrit/lit la colonne
 * {@code terminal_actions.embedding vector(1536)} par SQL natif — jamais mappée en JPA.
 *
 * <p><b>PostgreSQL uniquement</b> : appelée seulement sur le chemin sémantique actif (clé présente,
 * prod = PostgreSQL). En H2 (tests/dev), le sémantique est éteint et cette classe n'est jamais
 * sollicitée.</p>
 *
 * <p><b>Isolation</b> : chaque lecture filtre {@code user_id} et le poste (ou le terminal hébergé),
 * et ne considère que les attentes <b>ouvertes</b>.</p>
 */
@Component
public class TerminalActionEmbeddingStore {

    private static final String OPEN = "status IN ('A_FAIRE', 'DEMANDE')";

    private final JdbcTemplate jdbcTemplate;

    public TerminalActionEmbeddingStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Range l'embedding d'une attente. */
    public void store(UUID actionId, float[] embedding) {
        if (actionId == null || embedding == null || embedding.length == 0) {
            return;
        }
        jdbcTemplate.update("UPDATE terminal_actions SET embedding = CAST(? AS vector) WHERE id = ?",
                toVectorLiteral(embedding), actionId);
    }

    /**
     * Les attentes ouvertes du périmètre les plus proches, du plus proche au plus lointain.
     *
     * @param hostId      le poste ; {@code null} pour un terminal hébergé (alors {@code workspaceId})
     * @param workspaceId le terminal, utilisé seulement sans poste
     */
    public List<Scored> searchOpen(UUID userId, UUID hostId, UUID workspaceId, float[] query, int topN) {
        if (userId == null || query == null || topN <= 0 || (hostId == null && workspaceId == null)) {
            return List.of();
        }
        String literal = toVectorLiteral(query);
        String scope = hostId != null ? "host_id = ?" : "workspace_id = ?";
        String sql = "SELECT id, embedding <=> CAST(? AS vector) AS distance FROM terminal_actions "
                + "WHERE user_id = ? AND " + scope + " AND " + OPEN + " AND embedding IS NOT NULL "
                + "ORDER BY embedding <=> CAST(? AS vector) ASC LIMIT ?";
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new Scored(rs.getObject("id", UUID.class), rs.getDouble("distance")),
                literal, userId, hostId != null ? hostId : workspaceId, literal, topN);
    }

    /** Les attentes ouvertes du périmètre encore sans embedding (rattrapage borné). */
    public List<Unembedded> findOpenUnembedded(UUID userId, UUID hostId, UUID workspaceId, int limit) {
        if (userId == null || limit <= 0 || (hostId == null && workspaceId == null)) {
            return List.of();
        }
        String scope = hostId != null ? "host_id = ?" : "workspace_id = ?";
        String sql = "SELECT id, description FROM terminal_actions WHERE user_id = ? AND " + scope
                + " AND " + OPEN + " AND embedding IS NULL ORDER BY created_at DESC LIMIT ?";
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new Unembedded(rs.getObject("id", UUID.class), rs.getString("description")),
                userId, hostId != null ? hostId : workspaceId, limit);
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

    /** Une attente candidate : son id et la distance cosine (plus petit = plus proche). */
    public record Scored(UUID id, double distance) {
    }

    /** Une attente à embeddre. */
    public record Unembedded(UUID id, String description) {
    }
}
