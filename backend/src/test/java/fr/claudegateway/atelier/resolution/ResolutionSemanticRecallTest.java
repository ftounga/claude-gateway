package fr.claudegateway.atelier.resolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.recall.RecallSemanticProperties;
import fr.claudegateway.atelier.resolution.ResolutionMemoryEmbeddingStore.ScoredResolution;
import fr.claudegateway.rag.provider.EmbeddingProvider;

/**
 * Le rappel sémantique de la mémoire de résolutions (F-148 / SF-148-11).
 *
 * <p>Ce qu'il garantit : éteint sans clé (aucun appel réseau), recherche isolée déléguée au store, et
 * repli silencieux (liste vide) sur toute défaillance. L'exécuteur est synchrone ici pour observer
 * l'embedding best-effort.</p>
 */
class ResolutionSemanticRecallTest {

    private final EmbeddingProvider provider = mock(EmbeddingProvider.class);
    private final ResolutionMemoryEmbeddingStore store = mock(ResolutionMemoryEmbeddingStore.class);
    private final Executor directExecutor = Runnable::run;

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID resolutionId = UUID.randomUUID();

    private ResolutionSemanticRecall semantic(boolean configured) {
        RecallSemanticProperties props = new RecallSemanticProperties(
                Boolean.TRUE, configured ? "secret-key" : null, null, null, null, 5, null, null);
        return new ResolutionSemanticRecall(provider, store, props, directExecutor, 0.30);
    }

    @Test
    @DisplayName("isEnabled suit isConfigured (clé présente)")
    void isEnabledFollowsConfiguration() {
        assertThat(semantic(true).isEnabled()).isTrue();
        assertThat(semantic(false).isEnabled()).isFalse();
    }

    @Test
    @DisplayName("éteint : recherche → vide, aucun appel au fournisseur")
    void searchDisabledReturnsEmpty() {
        assertThat(semantic(false).searchSimilarQuestions(userId, hostId, "proxy", 5)).isEmpty();
        verifyNoInteractions(provider, store);
    }

    @Test
    @DisplayName("activé : embed la question entrante puis délègue au store isolé")
    void searchEmbedsThenDelegatesToStore() {
        when(provider.embed(List.of("configurer le proxy"))).thenReturn(List.of(new float[] {0.1f, 0.2f}));
        when(store.searchSimilarQuestions(eq(userId), eq(hostId), any(float[].class), eq(5)))
                .thenReturn(List.of(new ScoredResolution(resolutionId, 0.1)));

        List<ScoredResolution> hits =
                semantic(true).searchSimilarQuestions(userId, hostId, "configurer le proxy", 5);

        assertThat(hits).extracting(ScoredResolution::id).containsExactly(resolutionId);
        verify(store).searchSimilarQuestions(eq(userId), eq(hostId), any(float[].class), eq(5));
    }

    @Test
    @DisplayName("échec du fournisseur → liste vide (repli Jaccard côté appelant)")
    void searchProviderFailureReturnsEmpty() {
        when(provider.embed(any())).thenThrow(new RuntimeException("boom"));

        assertThat(semantic(true).searchSimilarQuestions(userId, hostId, "proxy réseau", 5)).isEmpty();
        verify(store, org.mockito.Mockito.never())
                .searchSimilarQuestions(any(), any(), any(float[].class), anyInt());
    }

    @Test
    @DisplayName("activé : embedQuestionAsync embed la question et range le vecteur par id")
    void embedStoresVectorById() {
        when(provider.embed(List.of("configurer le proxy"))).thenReturn(List.of(new float[] {0.3f, 0.4f}));

        semantic(true).embedQuestionAsync(resolutionId, "configurer le proxy");

        verify(store).store(eq(resolutionId), any(float[].class));
    }

    @Test
    @DisplayName("éteint ou entrée vide : embedQuestionAsync est un no-op")
    void embedNoOpWhenDisabledOrBlank() {
        semantic(false).embedQuestionAsync(resolutionId, "configurer le proxy");
        semantic(true).embedQuestionAsync(resolutionId, "   ");
        semantic(true).embedQuestionAsync(null, "configurer le proxy");
        verifyNoInteractions(store);
    }
}
