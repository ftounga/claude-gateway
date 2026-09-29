package fr.claudegateway.atelier.recall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.rag.provider.EmbeddingProvider;
import fr.claudegateway.rag.provider.EmbeddingProviderException;

/**
 * Le service sémantique (F-162 / SF-162-06) : gating par la config, embedding à l'écriture
 * <b>best-effort</b> (un échec ne lève pas), et recherche qui renvoie du vide sur toute défaillance —
 * ce qui fait retomber {@code recall} sur le mot-clé. L'{@code EmbeddingProvider} est mocké (le vrai
 * chemin pgvector est à valider en prod).
 */
@ExtendWith(MockitoExtension.class)
class AtelierSemanticRecallServiceTest {

    @Mock private EmbeddingProvider embeddingProvider;
    @Mock private AtelierMessageEmbeddingStore store;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();

    /** Exécuteur synchrone : la tâche best-effort tourne inline pour être vérifiable. */
    private AtelierSemanticRecallService service(String apiKey) {
        RecallSemanticProperties props = new RecallSemanticProperties(
                true, apiKey, null, null, null, null, null, null);
        return new AtelierSemanticRecallService(embeddingProvider, store, props, Runnable::run);
    }

    @Test
    @DisplayName("éteint sans clé : isEnabled faux, aucun embed ni recherche")
    void disabledWithoutKey() {
        AtelierSemanticRecallService service = service(null);

        assertThat(service.isEnabled()).isFalse();
        service.embedAsync(messageId, "du contenu");
        assertThat(service.search(userId, workspaceId, "requête", 5)).isEmpty();

        verifyNoInteractions(embeddingProvider, store);
    }

    @Test
    @DisplayName("embedAsync : calcule l'embedding et le range (chemin nominal)")
    void embedStoresVector() {
        when(embeddingProvider.embed(any())).thenReturn(List.of(new float[] {0.1f, 0.2f}));
        AtelierSemanticRecallService service = service("sk-key");

        service.embedAsync(messageId, "adressage réseau VPC");

        verify(embeddingProvider).embed(List.of("adressage réseau VPC"));
        verify(store).store(eq(messageId), any(float[].class));
    }

    @Test
    @DisplayName("embedAsync best-effort : le provider jette → aucune exception, rien n'est rangé")
    void embedIsBestEffort() {
        when(embeddingProvider.embed(any())).thenThrow(new EmbeddingProviderException("boom"));
        AtelierSemanticRecallService service = service("sk-key");

        // Ne doit PAS lever.
        service.embedAsync(messageId, "contenu");

        verify(store, never()).store(any(), any());
    }

    @Test
    @DisplayName("search : embed la requête puis délègue au store, isolé user + workspace")
    void searchDelegatesToStore() {
        UUID hit = UUID.randomUUID();
        when(embeddingProvider.embed(any())).thenReturn(List.of(new float[] {0.3f}));
        when(store.searchSimilar(eq(userId), eq(workspaceId), any(float[].class), eq(5)))
                .thenReturn(List.of(hit));
        AtelierSemanticRecallService service = service("sk-key");

        assertThat(service.search(userId, workspaceId, "VPC CIDR", 5)).containsExactly(hit);
        verify(store).searchSimilar(eq(userId), eq(workspaceId), any(float[].class), eq(5));
    }

    @Test
    @DisplayName("search : échec d'embedding → vide (repli mot-clé côté appelant), aucune recherche")
    void searchFallsBackOnEmbeddingFailure() {
        when(embeddingProvider.embed(any())).thenThrow(new EmbeddingProviderException("down"));
        AtelierSemanticRecallService service = service("sk-key");

        assertThat(service.search(userId, workspaceId, "requête", 5)).isEmpty();
        verify(store, never()).searchSimilar(any(), any(), any(), anyInt());
    }
}
