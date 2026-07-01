package fr.claudegateway.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import fr.claudegateway.ocr.Document;
import fr.claudegateway.ocr.DocumentRepository;
import fr.claudegateway.ocr.DocumentStatus;
import fr.claudegateway.ocr.OcrMode;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Test d'intégration de l'ingestion RAG (F-06 / SF-06-01) sur H2 : fournisseur d'embeddings stub +
 * store vectoriel no-op (défauts). Vérifie la persistance réelle des chunks, la transition
 * {@code EXTRACTED -> INDEXED}, le {@code chunkCount} et l'isolation {@code user_id}.
 */
@SpringBootTest
@ActiveProfiles("test")
class IngestionIntegrationTest {

    @Autowired
    private IngestionService ingestionService;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private ChunkRepository chunkRepository;
    @Autowired
    private UserRepository userRepository;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        chunkRepository.deleteAll();
        documentRepository.deleteAll();
        userRepository.deleteAll();
        alice = userRepository.save(User.builder()
                .email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        bob = userRepository.save(User.builder()
                .email("bob@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
    }

    private Document extractedDoc(UUID userId, String text) {
        return documentRepository.save(Document.builder()
                .userId(userId).filename("contrat.pdf").mediaType("application/pdf")
                .sizeBytes(10).status(DocumentStatus.EXTRACTED).ocrMode(OcrMode.ASYNC)
                .extractedText(text).build());
    }

    @Test
    void ingestPersistsChunksAndIndexesDocument() {
        String text = "Le présent contrat définit les obligations respectives des parties signataires "
                + "ainsi que les modalités d'exécution et de résiliation applicables.";
        Document doc = extractedDoc(alice.getId(), text);

        int count = ingestionService.ingest(doc);

        assertThat(count).isGreaterThan(0);
        Document reloaded = documentRepository.findById(doc.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(reloaded.getChunkCount()).isEqualTo(count);

        List<Chunk> chunks = chunkRepository.findByDocumentIdAndUserIdOrderByChunkIndexAsc(
                doc.getId(), alice.getId());
        assertThat(chunks).hasSize(count);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.getUserId()).isEqualTo(alice.getId());
            assertThat(c.getText()).isNotBlank();
        });
        assertThat(chunks.get(0).getChunkIndex()).isZero();
    }

    @Test
    void chunksAreIsolatedByUser() {
        Document aliceDoc = extractedDoc(alice.getId(), "texte confidentiel d'Alice à indexer ici");
        ingestionService.ingest(aliceDoc);

        // Bob ne voit aucun chunk du document d'Alice via son propre user_id.
        assertThat(chunkRepository.findByDocumentIdAndUserIdOrderByChunkIndexAsc(
                aliceDoc.getId(), bob.getId())).isEmpty();
        assertThat(chunkRepository.countByDocumentIdAndUserId(aliceDoc.getId(), bob.getId())).isZero();
        assertThat(chunkRepository.countByDocumentIdAndUserId(aliceDoc.getId(), alice.getId()))
                .isGreaterThan(0);
    }

    @Test
    void reingestionIsIdempotent() {
        Document doc = extractedDoc(alice.getId(), "mot mot mot mot mot mot mot mot mot mot");
        int first = ingestionService.ingest(doc);
        // Repasser le document en EXTRACTED pour rejouer l'ingestion.
        doc.setStatus(DocumentStatus.EXTRACTED);
        documentRepository.save(doc);
        int second = ingestionService.ingest(doc);

        assertThat(second).isEqualTo(first);
        assertThat(chunkRepository.countByDocumentIdAndUserId(doc.getId(), alice.getId()))
                .isEqualTo(first);
    }
}
