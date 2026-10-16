package fr.claudegateway.atelier.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.recall.RecallSemanticProperties;
import fr.claudegateway.rag.provider.EmbeddingProvider;

/**
 * Le rattrapage des embeddings au démarrage (F-180 / SF-180-01, D8) : éteint sans clé, par lots,
 * borné, chaque vecteur sur sa ligne, et silencieux en panne.
 */
class TerminalActionEmbeddingBackfillTest {

    private final EmbeddingProvider provider = mock(EmbeddingProvider.class);
    private final TerminalActionEmbeddingStore store = mock(TerminalActionEmbeddingStore.class);
    private final RecallSemanticProperties properties = mock(RecallSemanticProperties.class);
    private TerminalActionSemanticDedup semantic;

    @BeforeEach
    void setUp() {
        semantic = new TerminalActionSemanticDedup(provider, store, properties, Runnable::run, 0.20);
        when(properties.isConfigured()).thenReturn(true);
        when(provider.embed(anyList())).thenAnswer(i -> {
            List<?> texts = i.getArgument(0);
            List<float[]> out = new ArrayList<>();
            texts.forEach(t -> out.add(new float[] {0.1f}));
            return out;
        });
    }

    private List<TerminalActionEmbeddingStore.Unembedded> rows(int n) {
        List<TerminalActionEmbeddingStore.Unembedded> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new TerminalActionEmbeddingStore.Unembedded(UUID.randomUUID(), "Attente " + i));
        }
        return out;
    }

    @Test
    @DisplayName("sans clé : rien n'est lu ni appelé")
    void offWithoutKey() {
        when(properties.isConfigured()).thenReturn(false);
        new TerminalActionEmbeddingBackfill(semantic, Runnable::run, true).onReady();
        verifyNoInteractions(provider, store);
    }

    @Test
    @DisplayName("coupe-circuit : rien")
    void offBySwitch() {
        new TerminalActionEmbeddingBackfill(semantic, Runnable::run, false).onReady();
        verifyNoInteractions(provider, store);
    }

    @Test
    @DisplayName("par lots de 50, chaque vecteur sur sa propre ligne")
    void batches() {
        List<TerminalActionEmbeddingStore.Unembedded> missing = rows(120);
        when(store.findAllUnembedded(TerminalActionEmbeddingBackfill.MAX_PER_START)).thenReturn(missing);

        int stored = new TerminalActionEmbeddingBackfill(semantic, Runnable::run, true).run();

        assertThat(stored).isEqualTo(120);
        verify(provider, times(3)).embed(anyList());
        missing.forEach(row -> verify(store).store(org.mockito.ArgumentMatchers.eq(row.id()), any()));
    }

    @Test
    @DisplayName("fournisseur en panne : aucun échec, 0 rangé")
    void silentOnFailure() {
        when(store.findAllUnembedded(TerminalActionEmbeddingBackfill.MAX_PER_START)).thenReturn(rows(3));
        when(provider.embed(anyList())).thenThrow(new IllegalStateException("down"));

        assertThat(new TerminalActionEmbeddingBackfill(semantic, Runnable::run, true).run()).isZero();
    }
}
