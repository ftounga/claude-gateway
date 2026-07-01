package fr.claudegateway.ask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ModelCatalog;
import fr.claudegateway.ask.AskService.AskResult;
import fr.claudegateway.chat.UnsupportedModelException;
import fr.claudegateway.ocr.Document;
import fr.claudegateway.ocr.DocumentRepository;
import fr.claudegateway.ocr.DocumentStatus;
import fr.claudegateway.ocr.OcrMode;
import fr.claudegateway.quota.QuotaExceededException;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.rag.Chunk;
import fr.claudegateway.rag.ChunkRepository;
import fr.claudegateway.rag.provider.EmbeddingProvider;
import fr.claudegateway.rag.store.EmbeddingStore;
import fr.claudegateway.rag.store.ScoredChunk;

/**
 * Tests unitaires du cœur du Q&A documentaire (F-07 / SF-07-01) : orchestration quota → embedding →
 * recherche isolée → prompt cité → relais Claude → usage, repli sans contexte, validation de modèle et
 * isolation {@code user_id}. Toutes les dépendances (fournisseurs, store, repositories, quota) sont
 * mockées : aucun appel réseau ni base réelle.
 */
@ExtendWith(MockitoExtension.class)
class AskServiceTest {

    @Mock
    private EmbeddingProvider embeddingProvider;
    @Mock
    private EmbeddingStore embeddingStore;
    @Mock
    private ChunkRepository chunkRepository;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private AIProvider aiProvider;
    @Mock
    private ModelCatalog modelCatalog;
    @Mock
    private QuotaService quotaService;

    private AskService askService;

    private final UUID alice = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        askService = new AskService(embeddingProvider, embeddingStore, chunkRepository,
                documentRepository, aiProvider, modelCatalog, quotaService,
                new AskProperties(5, 240));
    }

    private Chunk chunk(int index, String text) {
        return Chunk.builder()
                .id(UUID.randomUUID())
                .documentId(documentId)
                .userId(alice)
                .chunkIndex(index)
                .text(text)
                .build();
    }

    private Document document() {
        return Document.builder()
                .id(documentId).userId(alice).filename("contrat.pdf").mediaType("application/pdf")
                .sizeBytes(10).status(DocumentStatus.INDEXED).ocrMode(OcrMode.ASYNC).build();
    }

    @Test
    void groundedAnswerBuildsCitedPromptAndCitations() {
        when(modelCatalog.defaultModel()).thenReturn("claude-opus-4-8");
        when(embeddingProvider.embed(anyList())).thenReturn(List.of(new float[] {0.1f, 0.2f}));
        Chunk c0 = chunk(0, "Obligation de confidentialité pendant cinq ans.");
        Chunk c1 = chunk(2, "Résiliation avec préavis de trois mois.");
        when(embeddingStore.search(eq(alice), any(), eq(5)))
                .thenReturn(List.of(new ScoredChunk(c0.getId(), 0.1), new ScoredChunk(c1.getId(), 0.3)));
        when(chunkRepository.findByIdInAndUserId(anyList(), eq(alice))).thenReturn(List.of(c1, c0));
        when(documentRepository.findByIdAndUserId(documentId, alice)).thenReturn(Optional.of(document()));
        when(aiProvider.complete(any())).thenReturn(
                new ChatCompletionResult("La confidentialité dure 5 ans [contrat.pdf:-:0].",
                        "claude-opus-4-8", 20, 10));

        AskResult result = askService.ask(alice, "  Quelle confidentialité ?  ", null, null);

        assertThat(result.grounded()).isTrue();
        assertThat(result.model()).isEqualTo("claude-opus-4-8");
        assertThat(result.citations()).hasSize(2);
        // Ordre de pertinence conservé (c0 avant c1, malgré l'ordre de retour du repository).
        assertThat(result.citations()).extracting(cit -> cit.chunkIndex()).containsExactly(0, 2);
        assertThat(result.citations()).allSatisfy(cit -> {
            assertThat(cit.documentId()).isEqualTo(documentId);
            assertThat(cit.filename()).isEqualTo("contrat.pdf");
        });

        // Le prompt transmis à Claude porte les marqueurs de citation [filename:page:chunkIndex].
        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(aiProvider).complete(captor.capture());
        String prompt = captor.getValue().messages().get(0).content();
        assertThat(prompt).contains("[contrat.pdf:-:0]").contains("[contrat.pdf:-:2]")
                .contains("Quelle confidentialité ?");

        // Quota vérifié avant, consommation enregistrée après.
        verify(quotaService).assertWithinQuota(alice);
        verify(quotaService).recordUsage(alice, 20, 10);
    }

    @Test
    void fallbackWhenNoChunksAnswersUngroundedWithoutCitations() {
        when(modelCatalog.defaultModel()).thenReturn("claude-opus-4-8");
        when(embeddingProvider.embed(anyList())).thenReturn(List.of(new float[] {0.1f}));
        when(embeddingStore.search(eq(alice), any(), anyInt())).thenReturn(List.of());
        when(aiProvider.complete(any())).thenReturn(
                new ChatCompletionResult("Réponse générale.", "claude-opus-4-8", 5, 3));

        AskResult result = askService.ask(alice, "Une question", null, null);

        assertThat(result.grounded()).isFalse();
        assertThat(result.citations()).isEmpty();
        assertThat(result.answer()).isEqualTo("Réponse générale.");
        // Aucun chargement de chunks/documents en repli.
        verify(chunkRepository, never()).findByIdInAndUserId(anyList(), any());
        verifyNoInteractions(documentRepository);
        verify(quotaService).recordUsage(alice, 5, 3);
    }

    @Test
    void quotaExceededStopsBeforeAnyProviderCall() {
        org.mockito.Mockito.doThrow(new QuotaExceededException("atteint"))
                .when(quotaService).assertWithinQuota(alice);

        assertThatThrownBy(() -> askService.ask(alice, "Une question", null, null))
                .isInstanceOf(QuotaExceededException.class);

        verifyNoInteractions(embeddingProvider, embeddingStore, aiProvider);
        verify(quotaService, never()).recordUsage(any(), anyInt(), anyInt());
    }

    @Test
    void unsupportedModelIsRejectedBeforeEmbedding() {
        when(modelCatalog.supports("mystere")).thenReturn(false);

        assertThatThrownBy(() -> askService.ask(alice, "Une question", "mystere", null))
                .isInstanceOf(UnsupportedModelException.class);

        verifyNoInteractions(embeddingProvider, embeddingStore, aiProvider);
        verify(quotaService, never()).recordUsage(any(), anyInt(), anyInt());
    }

    @Test
    void isolationAppliedOnSearchAndReloadAndTopKClamped() {
        when(modelCatalog.defaultModel()).thenReturn("claude-opus-4-8");
        when(embeddingProvider.embed(anyList())).thenReturn(List.of(new float[] {0.1f}));
        Chunk c0 = chunk(0, "texte");
        when(embeddingStore.search(eq(alice), any(), eq(20)))
                .thenReturn(List.of(new ScoredChunk(c0.getId(), 0.2)));
        when(chunkRepository.findByIdInAndUserId(anyList(), eq(alice))).thenReturn(List.of(c0));
        when(documentRepository.findByIdAndUserId(documentId, alice)).thenReturn(Optional.of(document()));
        when(aiProvider.complete(any()))
                .thenReturn(new ChatCompletionResult("ok", "claude-opus-4-8", 1, 1));

        // topK demandé au-delà de la borne (MAX_TOP_K=20) → clampé à 20.
        askService.ask(alice, "q", null, 999);

        verify(embeddingStore).search(eq(alice), any(), eq(20));
        verify(chunkRepository).findByIdInAndUserId(anyList(), eq(alice));
        verify(documentRepository).findByIdAndUserId(documentId, alice);
    }
}
