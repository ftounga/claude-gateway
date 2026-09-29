package fr.claudegateway.atelier.recall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.recall.AtelierMessageEmbeddingStore.UnembeddedMessage;
import fr.claudegateway.rag.provider.EmbeddingProvider;
import fr.claudegateway.rag.provider.EmbeddingProviderException;

/**
 * Le backfill (F-162 / SF-162-06) : borné, best-effort, éteint sans clé. L'{@code EmbeddingProvider} et
 * le store sont mockés (le vrai chemin pgvector est à valider en prod).
 */
@ExtendWith(MockitoExtension.class)
class AtelierEmbeddingBackfillServiceTest {

    @Mock private EmbeddingProvider embeddingProvider;
    @Mock private AtelierMessageEmbeddingStore store;

    private AtelierEmbeddingBackfillService service(String apiKey, int batchSize, int maxPerRun) {
        RecallSemanticProperties props = new RecallSemanticProperties(true, apiKey, null, null, null, null,
                null, new RecallSemanticProperties.Backfill(true, batchSize, maxPerRun, null));
        return new AtelierEmbeddingBackfillService(embeddingProvider, store, props);
    }

    private static List<UnembeddedMessage> batch(int n) {
        List<UnembeddedMessage> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new UnembeddedMessage(UUID.randomUUID(), "contenu " + i));
        }
        return list;
    }

    private static List<float[]> vectors(int n) {
        List<float[]> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new float[] {0.1f});
        }
        return list;
    }

    @Test
    @DisplayName("éteint sans clé : ne fait rien")
    void noopWithoutKey() {
        assertThat(service(null, 50, 500).runOnce()).isZero();
        verifyNoInteractions(embeddingProvider, store);
    }

    @Test
    @DisplayName("draine par lots jusqu'à épuisement, borné, et range chaque vecteur")
    void drainsInBatches() {
        when(store.findUnembeddedBatch(anyInt()))
                .thenReturn(batch(2))   // premier lot plein
                .thenReturn(batch(1))   // deuxième lot partiel => dernier
                .thenReturn(List.of()); // plus rien (non atteint : lot partiel arrête déjà)
        when(embeddingProvider.embed(any())).thenReturn(vectors(2)).thenReturn(vectors(1));

        int done = service("sk-key", 2, 500).runOnce();

        // 2 (lot plein) + 1 (lot partiel) = 3, puis arrêt (lot < demandé).
        assertThat(done).isEqualTo(3);
        verify(store, times(3)).store(any(), any());
    }

    @Test
    @DisplayName("respecte le plafond max-per-run")
    void honoursMaxPerRun() {
        when(store.findUnembeddedBatch(anyInt())).thenReturn(batch(2));
        when(embeddingProvider.embed(any())).thenReturn(vectors(2));

        int done = service("sk-key", 2, 2).runOnce(); // plafond = 2 => un seul lot

        assertThat(done).isEqualTo(2);
        verify(store, times(2)).store(any(), any());
    }

    @Test
    @DisplayName("best-effort : un embedding de lot en échec arrête la passe sans lever")
    void stopsOnBatchFailure() {
        when(store.findUnembeddedBatch(anyInt())).thenReturn(batch(2));
        when(embeddingProvider.embed(any())).thenThrow(new EmbeddingProviderException("boom"));

        int done = service("sk-key", 2, 500).runOnce();

        assertThat(done).isZero();
        verify(store, never()).store(any(), any());
    }
}
