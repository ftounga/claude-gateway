package fr.claudegateway.atelier.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.recall.RecallSemanticProperties;
import fr.claudegateway.rag.provider.EmbeddingProvider;

/**
 * Le dédoublonnage par le sens (F-175 / SF-175-03) : éteint sans clé, seuil exigeant, rattrapage des
 * attentes sans vecteur, et « aucun doublon » sur toute panne — on inscrit plutôt que de perdre.
 */
class TerminalActionSemanticDedupTest {

    private final EmbeddingProvider provider = mock(EmbeddingProvider.class);
    private final TerminalActionEmbeddingStore store = mock(TerminalActionEmbeddingStore.class);
    private final RecallSemanticProperties properties = mock(RecallSemanticProperties.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final float[] vector = {0.1f, 0.2f};

    private TerminalActionSemanticDedup dedup;

    @BeforeEach
    void setUp() {
        dedup = new TerminalActionSemanticDedup(provider, store, properties, Runnable::run, 0.20);
        when(properties.isConfigured()).thenReturn(true);
        when(provider.embed(anyList())).thenReturn(List.of(vector));
    }

    @Test
    @DisplayName("sans clé : éteint, aucun appel au fournisseur")
    void offWithoutKey() {
        when(properties.isConfigured()).thenReturn(false);
        assertThat(dedup.findSimilarOpen(userId, hostId, workspaceId, "x")).isEmpty();
        dedup.embedAsync(UUID.randomUUID(), "x");
        verifyNoInteractions(provider, store);
    }

    @Test
    @DisplayName("sous le seuil : reconnue ; au-dessus : rien (silencieux plutôt que bruyant)")
    void threshold() {
        UUID near = UUID.randomUUID();
        when(store.searchOpen(eq(userId), eq(hostId), eq(workspaceId), any(), anyInt()))
                .thenReturn(List.of(new TerminalActionEmbeddingStore.Scored(near, 0.12)));
        assertThat(dedup.findSimilarOpen(userId, hostId, workspaceId, "accès forge CAPFM"))
                .hasValueSatisfying(m -> assertThat(m.actionId()).isEqualTo(near));

        when(store.searchOpen(eq(userId), eq(hostId), eq(workspaceId), any(), anyInt()))
                .thenReturn(List.of(new TerminalActionEmbeddingStore.Scored(near, 0.35)));
        assertThat(dedup.findSimilarOpen(userId, hostId, workspaceId, "autre chose")).isEmpty();
    }

    @Test
    @DisplayName("les attentes sans vecteur du périmètre sont rattrapées avant la comparaison")
    void catchesUpMissingVectors() {
        UUID old = UUID.randomUUID();
        when(store.findOpenUnembedded(userId, hostId, workspaceId, TerminalActionSemanticDedup.CATCH_UP_LIMIT))
                .thenReturn(List.of(new TerminalActionEmbeddingStore.Unembedded(old, "Demander le VPN")));
        when(store.searchOpen(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        dedup.findSimilarOpen(userId, hostId, workspaceId, "VPN");

        verify(store).store(old, vector);
    }

    @Test
    @DisplayName("panne du fournisseur : aucun doublon, jamais une exception")
    void failureMeansNoDuplicate() {
        when(store.findOpenUnembedded(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(provider.embed(anyList())).thenThrow(new IllegalStateException("HS"));
        assertThat(dedup.findSimilarOpen(userId, hostId, workspaceId, "x")).isEmpty();
        verify(store, never()).searchOpen(any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("l'embedding d'une nouvelle attente est rangé")
    void embedsANewAction() {
        UUID id = UUID.randomUUID();
        dedup.embedAsync(id, "Valider le budget");
        verify(store).store(id, vector);
    }
}
