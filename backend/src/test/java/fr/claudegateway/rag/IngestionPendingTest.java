package fr.claudegateway.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * Tests unitaires de la sélection/boucle d'auto-indexation (F-06 / SF-06-02) : réclamation
 * {@code INDEXING}, résilience par document, compte des indexés. Provider/store mockés.
 */
@ExtendWith(MockitoExtension.class)
class IngestionPendingTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private ChunkRepository chunkRepository;
    @Mock
    private EmbeddingProvider embeddingProvider;
    @Mock
    private EmbeddingStore embeddingStore;

    private IngestionService ingestionService;

    @BeforeEach
    void setUp() {
        Chunker chunker = new Chunker(new RagProperties("noop", new RagProperties.Chunk(4, 1)));
        ingestionService = new IngestionService(
                documentRepository, chunkRepository, chunker, embeddingProvider, embeddingStore);
    }

    private Document extracted(String text) {
        return Document.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID())
                .filename("d.pdf").mediaType("application/pdf").sizeBytes(1)
                .status(DocumentStatus.EXTRACTED).ocrMode(OcrMode.ASYNC)
                .extractedText(text).build();
    }

    @Test
    void indexesAllPendingDocuments() {
        Document a = extracted("mot mot mot mot mot mot");
        Document b = extracted("autre autre autre autre autre autre");
        when(documentRepository.findByStatus(DocumentStatus.EXTRACTED)).thenReturn(List.of(a, b));
        when(embeddingProvider.embed(anyList()))
                .thenReturn(List.of(new float[] {0.1f}, new float[] {0.2f}));
        when(chunkRepository.saveAllAndFlush(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        int indexed = ingestionService.ingestPending();

        assertThat(indexed).isEqualTo(2);
        assertThat(a.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(b.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    }

    @Test
    void oneFailureDoesNotStopTheBatch() {
        Document failing = extracted("va echouer echouer echouer echouer echouer");
        Document ok = extracted("va reussir reussir reussir reussir reussir");
        when(documentRepository.findByStatus(DocumentStatus.EXTRACTED))
                .thenReturn(List.of(failing, ok));
        // 1er doc : embed échoue -> FAILED ; 2e doc : embed renvoie 2 vecteurs -> INDEXED.
        when(embeddingProvider.embed(anyList()))
                .thenThrow(new EmbeddingProviderException("échec"))
                .thenReturn(List.of(new float[] {0.1f}, new float[] {0.2f}));
        when(chunkRepository.saveAllAndFlush(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        int indexed = ingestionService.ingestPending();

        assertThat(indexed).isEqualTo(1);
        assertThat(failing.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(ok.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    }

    @Test
    void noPendingReturnsZero() {
        when(documentRepository.findByStatus(DocumentStatus.EXTRACTED)).thenReturn(List.of());
        assertThat(ingestionService.ingestPending()).isZero();
    }
}
