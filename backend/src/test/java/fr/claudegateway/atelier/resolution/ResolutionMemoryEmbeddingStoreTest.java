package fr.claudegateway.atelier.resolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Le store vectoriel de la mémoire de résolutions (F-148 / SF-148-10).
 *
 * <p>Ce qu'il garantit : l'écriture range le vecteur par id ; la recherche est <b>toujours</b> isolée
 * {@code user_id} ET {@code host_id} et bornée. Le type {@code vector} n'existant pas en H2, on vérifie le
 * SQL émis et les arguments passés au {@link JdbcTemplate} (patron de la couche vectorielle du projet).</p>
 */
class ResolutionMemoryEmbeddingStoreTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ResolutionMemoryEmbeddingStore store = new ResolutionMemoryEmbeddingStore(jdbc);

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID resolutionId = UUID.randomUUID();

    @Test
    @DisplayName("store range le vecteur par id via CAST(? AS vector)")
    void storeWritesVectorById() {
        store.store(resolutionId, new float[] {0.1f, 0.2f, 0.3f});

        // Varargs : des matchers explicites (capturer un seul captor en position varargs change l'arité).
        verify(jdbc).update(
                eq("UPDATE resolution_memory SET embedding = CAST(? AS vector) WHERE id = ?"),
                eq("[0.1,0.2,0.3]"), eq(resolutionId));
    }

    @Test
    @DisplayName("store : id nul, vecteur nul ou vide → aucune écriture")
    void storeGuardsAgainstEmptyInputs() {
        store.store(null, new float[] {0.1f});
        store.store(resolutionId, null);
        store.store(resolutionId, new float[] {});
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("searchSimilarQuestions filtre TOUJOURS user_id ET host_id, borné à topN")
    void searchIsolatesByUserAndHost() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        store.searchSimilarQuestions(userId, hostId, new float[] {0.1f, 0.2f}, 3);

        // SQL capturé, arguments varargs par matchers explicites (ordre : littéral SELECT, user, host,
        // littéral ORDER BY, topN) — isolation user_id ET host_id garantie par le WHERE.
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class),
                eq("[0.1,0.2]"), eq(userId), eq(hostId), eq("[0.1,0.2]"), eq(3));

        assertThat(sql.getValue())
                .contains("WHERE user_id = ? AND host_id = ?")
                .contains("embedding IS NOT NULL")
                .contains("ORDER BY embedding <=> CAST(? AS vector) ASC")
                .contains("LIMIT ?");
    }

    @Test
    @DisplayName("searchSimilarQuestions : entrées nulles ou topN ≤ 0 → liste vide, aucune requête")
    void searchGuardsAgainstInvalidInputs() {
        assertThat(store.searchSimilarQuestions(null, hostId, new float[] {0.1f}, 3)).isEmpty();
        assertThat(store.searchSimilarQuestions(userId, null, new float[] {0.1f}, 3)).isEmpty();
        assertThat(store.searchSimilarQuestions(userId, hostId, null, 3)).isEmpty();
        assertThat(store.searchSimilarQuestions(userId, hostId, new float[] {0.1f}, 0)).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("findUnembeddedBatch sélectionne les résolutions sans vecteur, question non vide, borné")
    void findUnembeddedBatchSelectsRowsWithoutVector() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        store.findUnembeddedBatch(50);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(50));
        assertThat(sql.getValue())
                .contains("FROM resolution_memory")
                .contains("embedding IS NULL")
                .contains("question IS NOT NULL AND question <> ''")
                .contains("LIMIT ?");
    }

    @Test
    @DisplayName("findUnembeddedBatch : limit ≤ 0 → liste vide, aucune requête")
    void findUnembeddedBatchGuardsLimit() {
        assertThat(store.findUnembeddedBatch(0)).isEmpty();
        assertThat(store.findUnembeddedBatch(-1)).isEmpty();
        verifyNoInteractions(jdbc);
    }
}
