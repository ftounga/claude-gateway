package fr.claudegateway.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.ocr.Document;
import fr.claudegateway.ocr.DocumentRepository;
import fr.claudegateway.ocr.DocumentStatus;
import fr.claudegateway.ocr.OcrMode;
import fr.claudegateway.rag.provider.EmbeddingProvider;
import fr.claudegateway.rag.provider.EmbeddingProviderException;
import fr.claudegateway.rag.store.EmbeddingStore;

/**
 * Tests unitaires du cœur d'ingestion RAG (F-06 / SF-06-01) : transitions de statut, chunking,
 * délégation au fournisseur d'embeddings (mocké) et au store vectoriel (mocké), idempotence et
 * isolation {@code user_id}. Aucun appel réseau ni base réelle.
 */
@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private ChunkRepository chunkRepository;
    @Mock
    private EmbeddingProvider embeddingProvider;
    @Mock
    private EmbeddingStore embeddingStore;

    private IngestionService ingestionService;

    private final UUID alice = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Chunker chunker = new Chunker(new RagProperties("noop", new RagProperties.Chunk(4, 1)));
        ingestionService = new IngestionService(
                documentRepository, chunkRepository, chunker, embeddingProvider, embeddingStore);
    }

    private Document extracted(String text) {
        return Document.builder()
                .id(UUID.randomUUID())
                .userId(alice)
                .filename("contrat.pdf")
                .mediaType("application/pdf")
                .sizeBytes(10)
                .status(DocumentStatus.EXTRACTED)
                .ocrMode(OcrMode.ASYNC)
                .extractedText(text)
                .build();
    }

    @Test
    void extractedDocumentBecomesIndexedWithChunks() {
        Document doc = extracted("mot1 mot2 mot3 mot4 mot5 mot6"); // 6 mots, fenêtre 4 overlap 1 => step 3 => 2 chunks
        when(embeddingProvider.embed(anyList()))
                .thenReturn(List.of(new float[] {0.1f}, new float[] {0.2f}));
        when(chunkRepository.saveAllAndFlush(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        int count = ingestionService.ingest(doc);

        assertThat(count).isEqualTo(2);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(doc.getChunkCount()).isEqualTo(2);
        // Idempotence : suppression des chunks pré-existants (isolation user_id) avant recréation.
        verify(chunkRepository).deleteByDocumentIdAndUserId(doc.getId(), alice);

        ArgumentCaptor<List<Chunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).saveAllAndFlush(captor.capture());
        List<Chunk> saved = captor.getValue();
        assertThat(saved).allSatisfy(c -> {
            assertThat(c.getUserId()).isEqualTo(alice);
            assertThat(c.getDocumentId()).isEqualTo(doc.getId());
        });
        assertThat(saved).extracting(Chunk::getChunkIndex).containsExactly(0, 1);
        // Un vecteur stocké par chunk.
        verify(embeddingStore, times(2)).store(any(), any());
    }

    @Test
    void blankTextIndexesWithZeroChunksAndNoEmbeddingCall() {
        Document doc = extracted("   ");
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        int count = ingestionService.ingest(doc);

        assertThat(count).isZero();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(doc.getChunkCount()).isZero();
        verify(chunkRepository).deleteByDocumentIdAndUserId(doc.getId(), alice);
        verify(embeddingProvider, never()).embed(anyList());
        verify(chunkRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void providerFailureMarksDocumentFailed() {
        Document doc = extracted("un texte à indexer");
        when(embeddingProvider.embed(anyList()))
                .thenThrow(new EmbeddingProviderException("échec amont"));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        int count = ingestionService.ingest(doc);

        assertThat(count).isZero();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(doc.getErrorMessage()).isEqualTo("Échec de l'indexation.");
        // Pas de suppression/recréation de chunks si l'embedding échoue avant la persistance.
        verify(chunkRepository, never()).deleteByDocumentIdAndUserId(any(), any());
        verify(chunkRepository, never()).saveAllAndFlush(anyList());
        verify(embeddingStore, never()).store(any(), any());
    }

    @Test
    void mismatchedVectorCountMarksFailed() {
        Document doc = extracted("mot1 mot2 mot3 mot4 mot5 mot6"); // 2 chunks attendus
        when(embeddingProvider.embed(anyList())).thenReturn(List.of(new float[] {0.1f})); // 1 seul vecteur
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        int count = ingestionService.ingest(doc);

        assertThat(count).isZero();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
        verify(chunkRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void nonExtractedDocumentIsIgnored() {
        Document doc = extracted("texte");
        doc.setStatus(DocumentStatus.INDEXED);

        int count = ingestionService.ingest(doc);

        assertThat(count).isZero();
        verify(embeddingProvider, never()).embed(anyList());
        verify(chunkRepository, never()).saveAllAndFlush(anyList());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void resumesDocumentAlreadyIndexing() {
        Document doc = extracted("mot1 mot2 mot3 mot4 mot5 mot6");
        doc.setStatus(DocumentStatus.INDEXING); // repris par le worker
        when(embeddingProvider.embed(anyList()))
                .thenReturn(List.of(new float[] {0.1f}, new float[] {0.2f}));
        when(chunkRepository.saveAllAndFlush(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        int count = ingestionService.ingest(doc);

        assertThat(count).isEqualTo(2);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        verify(embeddingStore, times(2)).store(any(), any());
    }
}
