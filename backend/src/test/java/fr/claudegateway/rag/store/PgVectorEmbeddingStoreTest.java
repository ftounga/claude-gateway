package fr.claudegateway.rag.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Tests unitaires de l'implémentation pgvector (F-06 stockage / F-07 recherche). Le {@link JdbcTemplate}
 * est mocké : on vérifie la forme paramétrée du SQL (isolation {@code user_id}, littéral vecteur, LIMIT)
 * et le court-circuit des entrées invalides — sans base Postgres réelle.
 */
@ExtendWith(MockitoExtension.class)
class PgVectorEmbeddingStoreTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private final UUID alice = UUID.randomUUID();

    @Test
    void searchIssuesIsolatedNearestNeighbourQuery() {
        PgVectorEmbeddingStore store = new PgVectorEmbeddingStore(jdbcTemplate);
        UUID hit = UUID.randomUUID();
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new ScoredChunk(hit, 0.42)));

        List<ScoredChunk> results = store.search(alice, new float[] {0.1f, 0.2f, 0.3f}, 5);

        assertThat(results).containsExactly(new ScoredChunk(hit, 0.42));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), argsCaptor.capture());

        String sql = sqlCaptor.getValue();
        assertThat(sql)
                .contains("user_id = ?")            // isolation multi-tenant
                .contains("<->")                     // distance L2 (index ivfflat vector_l2_ops)
                .contains("CAST(? AS vector)")       // paramétrage pgvector
                .contains("LIMIT ?");
        // Ordre des paramètres : littéral vecteur, user_id, topK.
        Object[] args = argsCaptor.getValue();
        assertThat(args[0]).isEqualTo("[0.1,0.2,0.3]");
        assertThat(args[1]).isEqualTo(alice);
        assertThat(args[2]).isEqualTo(5);
    }

    @Test
    void searchShortCircuitsOnInvalidInput() {
        PgVectorEmbeddingStore store = new PgVectorEmbeddingStore(jdbcTemplate);

        assertThat(store.search(alice, null, 5)).isEmpty();
        assertThat(store.search(alice, new float[] {0.1f}, 0)).isEmpty();

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void storeUpdatesVectorColumnById() {
        PgVectorEmbeddingStore store = new PgVectorEmbeddingStore(jdbcTemplate);
        UUID chunkId = UUID.randomUUID();

        store.store(chunkId, new float[] {0.5f, -0.5f});

        verify(jdbcTemplate).update(
                eq("UPDATE chunks SET embedding = CAST(? AS vector) WHERE id = ?"),
                eq("[0.5,-0.5]"), eq(chunkId));
    }
}
