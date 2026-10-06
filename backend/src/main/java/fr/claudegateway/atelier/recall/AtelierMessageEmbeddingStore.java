package fr.claudegateway.atelier.recall;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Stockage et recherche vectoriels des messages de conversation via <b>pgvector</b> (F-162 / SF-162-06),
 * sur le patron de {@code PgVectorEmbeddingStore} (RAG). Écrit/lit la colonne
 * {@code atelier_messages.embedding vector(1536)} par <b>SQL natif</b> — jamais mappée en JPA (Hibernate
 * ne connaît pas le type {@code vector}, et H2 ne l'a pas).
 *
 * <p><b>PostgreSQL uniquement.</b> Ces requêtes n'ont de sens que là où la colonne {@code vector} existe.
 * Elles ne sont appelées que sur le chemin sémantique actif ({@link AtelierSemanticRecallService}), lui
 * même conditionné à une clé présente (prod = PostgreSQL). En H2 (tests/dev), le sémantique est éteint et
 * cette classe n'est jamais sollicitée.</p>
 *
 * <p><b>Isolation multi-tenant</b> : la recherche filtre <b>toujours</b> {@code user_id} ET
 * {@code workspace_id}. Aucun contenu ni secret n'est manipulé ici pour la recherche (seulement des
 * identifiants et le vecteur numérique).</p>
 */
@Component
public class AtelierMessageEmbeddingStore {

    private final JdbcTemplate jdbcTemplate;

    public AtelierMessageEmbeddingStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Range l'embedding d'un message (cast texte -> vector, API paramétrée officielle pgvector). */
    public void store(UUID messageId, float[] embedding) {
        if (messageId == null || embedding == null || embedding.length == 0) {
            return;
        }
        jdbcTemplate.update(
                "UPDATE atelier_messages SET embedding = CAST(? AS vector) WHERE id = ?",
                toVectorLiteral(embedding), messageId);
    }

    /**
     * Recherche des plus proches voisins d'une requête, <b>isolée {@code user_id} + {@code workspace_id}</b>,
     * bornée à {@code topN}. L'opérateur {@code <=>} (distance cosine) exploite l'index hnsw
     * {@code vector_cosine_ops} de la migration 135. Le vecteur ne sert que dans le SELECT (alias réutilisé
     * par ORDER BY) : un seul paramètre vecteur. Renvoie les ids, du plus proche au plus lointain.
     */
    public List<UUID> searchSimilar(UUID userId, UUID workspaceId, float[] queryEmbedding, int topN) {
        if (userId == null || workspaceId == null || queryEmbedding == null || topN <= 0) {
            return List.of();
        }
        String sql = "SELECT id FROM atelier_messages "
                + "WHERE user_id = ? AND workspace_id = ? AND embedding IS NOT NULL "
                + "ORDER BY embedding <=> CAST(? AS vector) ASC LIMIT ?";
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                userId, workspaceId, toVectorLiteral(queryEmbedding), topN);
    }

    /** Borne du nombre de fils d'une recherche à portée poste (F-178 / SF-178-01). */
    static final int MAX_WORKSPACES = 200;

    /**
     * Recherche des plus proches voisins <b>à portée poste</b> (F-178 / SF-178-01) : sur un ensemble de
     * fils (terminal du poste + sujets du poste, résolus par l'appelant), <b>toujours filtrée
     * {@code user_id}</b>. Ensemble borné à {@link #MAX_WORKSPACES}. Ids du plus proche au plus lointain.
     */
    public List<UUID> searchSimilarAcross(UUID userId, java.util.Collection<UUID> workspaceIds,
            float[] queryEmbedding, int topN) {
        if (userId == null || workspaceIds == null || workspaceIds.isEmpty() || queryEmbedding == null
                || topN <= 0) {
            return List.of();
        }
        List<UUID> ids = workspaceIds.stream().filter(java.util.Objects::nonNull).distinct()
                .limit(MAX_WORKSPACES).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT id FROM atelier_messages "
                + "WHERE user_id = ? AND workspace_id IN (" + placeholders + ") AND embedding IS NOT NULL "
                + "ORDER BY embedding <=> CAST(? AS vector) ASC LIMIT ?";
        List<Object> args = new java.util.ArrayList<>();
        args.add(userId);
        args.addAll(ids);
        args.add(toVectorLiteral(queryEmbedding));
        args.add(topN);
        return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getObject("id", UUID.class), args.toArray());
    }

    /**
     * Un lot de messages <b>sans embedding</b>, du plus récent au plus ancien, borné (F-162 / SF-162-06,
     * backfill). Balayage global (tous tenants) : embeddre un message écrit sur SA propre ligne ne croise
     * aucun tenant ; la recherche, elle, reste toujours filtrée. Ignore les contenus vides.
     */
    public List<UnembeddedMessage> findUnembeddedBatch(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String sql = "SELECT id, content FROM atelier_messages "
                + "WHERE embedding IS NULL AND content IS NOT NULL AND content <> '' "
                + "ORDER BY created_at DESC LIMIT ?";
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new UnembeddedMessage(rs.getObject("id", UUID.class), rs.getString("content")),
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

    /** Un message à embeddre (backfill) : son id et son contenu. */
    public record UnembeddedMessage(UUID id, String content) {
    }
}
