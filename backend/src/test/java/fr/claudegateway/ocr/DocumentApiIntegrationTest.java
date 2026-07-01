package fr.claudegateway.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.rag.Chunk;
import fr.claudegateway.rag.ChunkRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration de {@code /api/documents} (F-05 / SF-05-01) : OCR synchrone image, soumission
 * asynchrone PDF, validations, authentification et isolation {@code user_id}. Le fournisseur OCR par
 * défaut ({@code StubOcrProvider}) est utilisé — aucun appel AWS.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private JwtService jwtService;

    private User alice;
    private User bob;
    private String aliceToken;
    private String bobToken;

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
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
    }

    private MockMultipartFile png() {
        return new MockMultipartFile("file", "scan.png", "image/png", new byte[] {1, 2, 3, 4});
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "contrat.pdf", "application/pdf", new byte[] {5, 6, 7});
    }

    @Test
    void imageIsExtractedSynchronously() throws Exception {
        mockMvc.perform(multipart("/api/documents").file(png()).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.filename", is("scan.png")))
                .andExpect(jsonPath("$.mediaType", is("image/png")))
                .andExpect(jsonPath("$.status", is("EXTRACTED")));

        assertThat(documentRepository.findAll()).hasSize(1);
        Document saved = documentRepository.findAll().get(0);
        assertThat(saved.getUserId()).isEqualTo(alice.getId());
        assertThat(saved.getExtractedText()).isNotBlank();
    }

    @Test
    void pdfIsSubmittedForAsyncProcessing() throws Exception {
        mockMvc.perform(multipart("/api/documents").file(pdf()).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("PROCESSING")));

        Document saved = documentRepository.findAll().get(0);
        assertThat(saved.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(saved.getProviderJobId()).isNotBlank();
    }

    @Test
    void rejectsMissingFile() throws Exception {
        mockMvc.perform(multipart("/api/documents").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
        assertThat(documentRepository.findAll()).isEmpty();
    }

    @Test
    void rejectsUnsupportedType() throws Exception {
        MockMultipartFile exe = new MockMultipartFile(
                "file", "x.exe", "application/x-msdownload", new byte[] {1, 2, 3});
        mockMvc.perform(multipart("/api/documents").file(exe).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error", is("unsupported_file_type")));
        assertThat(documentRepository.findAll()).isEmpty();
    }

    @Test
    void rejectsUnauthenticated() throws Exception {
        mockMvc.perform(multipart("/api/documents").file(png()).contextPath("/api"))
                .andExpect(status().isUnauthorized());
        assertThat(documentRepository.findAll()).isEmpty();
    }

    @Test
    void getByIdEnforcesUserIsolation() throws Exception {
        Document aliceDoc = documentRepository.save(Document.builder()
                .userId(alice.getId()).filename("scan.png").mediaType("image/png")
                .sizeBytes(4).status(DocumentStatus.EXTRACTED).ocrMode(OcrMode.SYNC)
                .extractedText("secret d'Alice").build());

        // Bob ne peut pas lire le document d'Alice → 404 (indiscernable).
        mockMvc.perform(get("/api/documents/{id}", aliceDoc.getId()).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("not_found")));

        // Alice lit son propre document, texte extrait inclus.
        mockMvc.perform(get("/api/documents/{id}", aliceDoc.getId()).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extractedText", is("secret d'Alice")));
    }

    @Test
    void listReturnsOnlyOwnDocuments() throws Exception {
        documentRepository.save(Document.builder()
                .userId(alice.getId()).filename("a.png").mediaType("image/png")
                .sizeBytes(1).status(DocumentStatus.EXTRACTED).ocrMode(OcrMode.SYNC).build());
        documentRepository.save(Document.builder()
                .userId(bob.getId()).filename("b.png").mediaType("image/png")
                .sizeBytes(1).status(DocumentStatus.EXTRACTED).ocrMode(OcrMode.SYNC).build());

        mockMvc.perform(get("/api/documents").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].filename", is("a.png")));
    }

    @Test
    void statusEndpointReturnsLightweightState() throws Exception {
        Document doc = documentRepository.save(Document.builder()
                .userId(alice.getId()).filename("scan.png").mediaType("image/png")
                .sizeBytes(4).status(DocumentStatus.INDEXED).ocrMode(OcrMode.SYNC)
                .chunkCount(3).extractedText("texte extrait d'Alice").build());

        mockMvc.perform(get("/api/documents/{id}/status", doc.getId()).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(doc.getId().toString())))
                .andExpect(jsonPath("$.status", is("INDEXED")))
                .andExpect(jsonPath("$.chunkCount", is(3)))
                // Vue légère : ni texte extrait ni brut fournisseur exposés.
                .andExpect(jsonPath("$.extractedText").doesNotExist());
    }

    @Test
    void statusEndpointEnforcesUserIsolation() throws Exception {
        Document aliceDoc = documentRepository.save(Document.builder()
                .userId(alice.getId()).filename("scan.png").mediaType("image/png")
                .sizeBytes(4).status(DocumentStatus.EXTRACTED).ocrMode(OcrMode.SYNC).build());

        mockMvc.perform(get("/api/documents/{id}/status", aliceDoc.getId()).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("not_found")));
    }

    @Test
    void deleteRemovesDocumentAndCascadesChunks() throws Exception {
        Document doc = documentRepository.save(Document.builder()
                .userId(alice.getId()).filename("contrat.pdf").mediaType("application/pdf")
                .sizeBytes(10).status(DocumentStatus.INDEXED).ocrMode(OcrMode.ASYNC)
                .chunkCount(2).extractedText("texte").build());
        chunkRepository.save(Chunk.builder()
                .documentId(doc.getId()).userId(alice.getId()).chunkIndex(0).text("chunk 0").build());
        chunkRepository.save(Chunk.builder()
                .documentId(doc.getId()).userId(alice.getId()).chunkIndex(1).text("chunk 1").build());

        mockMvc.perform(delete("/api/documents/{id}", doc.getId()).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        assertThat(documentRepository.findById(doc.getId())).isEmpty();
        // Droit à l'effacement : les chunks dérivés (et leurs vecteurs) sont supprimés en cascade.
        assertThat(chunkRepository.countByDocumentIdAndUserId(doc.getId(), alice.getId())).isZero();

        // Un accès ultérieur renvoie 404.
        mockMvc.perform(get("/api/documents/{id}", doc.getId()).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteEnforcesUserIsolation() throws Exception {
        Document aliceDoc = documentRepository.save(Document.builder()
                .userId(alice.getId()).filename("scan.png").mediaType("image/png")
                .sizeBytes(4).status(DocumentStatus.EXTRACTED).ocrMode(OcrMode.SYNC).build());

        // Bob ne peut pas supprimer le document d'Alice → 404, document préservé.
        mockMvc.perform(delete("/api/documents/{id}", aliceDoc.getId()).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("not_found")));

        assertThat(documentRepository.findById(aliceDoc.getId())).isPresent();
    }

    @Test
    void deleteRejectsUnauthenticated() throws Exception {
        Document doc = documentRepository.save(Document.builder()
                .userId(alice.getId()).filename("scan.png").mediaType("image/png")
                .sizeBytes(4).status(DocumentStatus.EXTRACTED).ocrMode(OcrMode.SYNC).build());

        mockMvc.perform(delete("/api/documents/{id}", doc.getId()).contextPath("/api"))
                .andExpect(status().isUnauthorized());
        assertThat(documentRepository.findById(doc.getId())).isPresent();
    }
}
