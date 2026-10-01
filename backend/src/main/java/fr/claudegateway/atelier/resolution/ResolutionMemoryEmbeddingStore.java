package fr.claudegateway.atelier.resolution;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Stockage et recherche vectoriels des <b>questions</b> de la mémoire de résolutions via <b>pgvector</b>
 * (F-148 / SF-148-10), sur le patron de {@link fr.claudegateway.atelier.recall.AtelierMessageEmbeddingStore}
 * (SF-162-06). Écrit/lit la colonne {@code resolution_memory.embedding vector(1536)} par <b>SQL natif</b> —
 * jamais mappée en JPA (Hibernate ne connaît pas le type {@code vector}, et H2 ne l'a pas).
 *
 * <p><b>PostgreSQL uniquement.</b> Ces requêtes n'ont de sens que là où la colonne {@code vector} existe.
 * Elles ne sont appelées que sur le chemin sémantique actif ({@code ResolutionSemanticRecall}, SF-148-11),
 * lui-même conditionné à une clé présente (prod = PostgreSQL). En H2 (tests/dev), le sémantique est éteint
 * et cette classe n'est jamais sollicitée.</p>
 *
 * <p><b>Isolation multi-tenant</b> : la recherche filtre <b>toujours</b> {@code user_id} ET {@code host_id}
 * (la mémoire est par poste). Aucun contenu ni secret n'est manipulé ici pour la recherche (seulement des
 * identifiants et le vecteur numérique).</p>
 */
@Component
public class ResolutionMemoryEmbeddingStore {

    private final JdbcTemplate jdbcTemplate;

    public ResolutionMemoryEmbeddingStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Range l'embedding d'une résolution (cast texte -> vector, API paramétrée officielle pgvector). */
    public void store(UUID resolutionId, float[] embedding) {
        if (resolutionId == null || embedding == null || embedding.length == 0) {
            return;
        }
        jdbcTemplate.update(
                "UPDATE resolution_memory SET embedding = CAST(? AS vector) WHERE id = ?",
                toVectorLiteral(embedding), resolutionId);
    }

    /**
     * Recherche des résolutions les plus proches d'une question, <b>isolée {@code user_id} + {@code host_id}</b>,
     * bornée à {@code topN}. L'opérateur {@code <=>} (distance cosine) exploite l'index hnsw
     * {@code vector_cosine_ops} de la migration 137. La distance est renvoyée pour que l'appelant applique un
     * <b>seuil exigeant</b> (anti-faux-positif). Renvoie, du plus proche au plus lointain, les
     * {@link ScoredResolution} (id + distance cosine).
     */
    public List<ScoredResolution> searchSimilarQuestions(UUID userId, UUID hostId, float[] queryEmbedding,
            int topN) {
        if (userId == null || hostId == null || queryEmbedding == null || topN <= 0) {
            return List.of();
        }
        String literal = toVectorLiteral(queryEmbedding);
        // Le vecteur apparaît deux fois (SELECT distance + ORDER BY) : deux paramètres vecteur identiques.
        String sql = "SELECT id, embedding <=> CAST(? AS vector) AS distance FROM resolution_memory "
                + "WHERE user_id = ? AND host_id = ? AND embedding IS NOT NULL "
                + "ORDER BY embedding <=> CAST(? AS vector) ASC LIMIT ?";
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new ScoredResolution(rs.getObject("id", UUID.class), rs.getDouble("distance")),
                literal, userId, hostId, literal, topN);
    }

    /**
     * Un lot de résolutions <b>sans embedding</b>, du plus récent au plus ancien, borné (F-148 / SF-148-12,
     * backfill). Balayage global (tous tenants) : embeddre la <b>question</b> d'une résolution sur SA propre
     * ligne ne croise aucun tenant ; la recherche, elle, reste toujours filtrée {@code (user_id, host_id)}.
     * Ignore les questions vides.
     */
    public List<UnembeddedResolution> findUnembeddedBatch(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String sql = "SELECT id, question FROM resolution_memory "
                + "WHERE embedding IS NULL AND question IS NOT NULL AND question <> '' "
                + "ORDER BY created_at DESC LIMIT ?";
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new UnembeddedResolution(rs.getObject("id", UUID.class),
                        rs.getString("question")),
                limit);
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

    /** Une résolution candidate : son id et la distance cosine à la question entrante (plus petit = plus proche). */
    public record ScoredResolution(UUID id, double distance) {
    }

    /** Une résolution à embeddre (backfill) : son id et sa question. */
    public record UnembeddedResolution(UUID id, String question) {
    }
}
