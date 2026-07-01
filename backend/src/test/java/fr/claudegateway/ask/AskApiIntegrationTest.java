package fr.claudegateway.ask;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ProviderFileReference;
import fr.claudegateway.ai.ProviderFileUpload;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.ocr.Document;
import fr.claudegateway.ocr.DocumentRepository;
import fr.claudegateway.ocr.DocumentStatus;
import fr.claudegateway.ocr.OcrMode;
import fr.claudegateway.rag.Chunk;
import fr.claudegateway.rag.ChunkRepository;
import fr.claudegateway.rag.store.EmbeddingStore;
import fr.claudegateway.rag.store.ScoredChunk;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration de {@code /api/ask} (F-07 / SF-07-01) : flux nominal cité, repli sans contexte,
 * validation, authentification et <b>isolation {@code user_id}</b> (défense en profondeur du
 * rechargement des chunks). Le fournisseur IA et le store vectoriel sont remplacés par des stubs
 * contrôlables — aucun appel réseau, aucune dépendance Postgres/pgvector.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AskApiIntegrationTest {

    /** Store vectoriel contrôlable : simule le moteur de recherche (les résultats sont pilotés). */
    static class StubEmbeddingStore implements EmbeddingStore {
        volatile List<ScoredChunk> nextHits = List.of();

        @Override
        public void store(UUID chunkId, float[] embedding) {
            // no-op : la persistance vectorielle n'est pas testée ici.
        }

        @Override
        public List<ScoredChunk> search(UUID userId, float[] queryEmbedding, int topK) {
            return nextHits;
        }
    }

    /** Fournisseur IA stub : renvoie une réponse fixe sans appeler Anthropic. */
    static class StubAIProvider implements AIProvider {
        volatile ChatCompletionRequest lastRequest;

        @Override
        public ChatCompletionResult complete(ChatCompletionRequest request) {
            this.lastRequest = request;
            return new ChatCompletionResult("Réponse de test.", request.model(), 10, 5);
        }

        @Override
        public ProviderFileReference uploadFile(ProviderFileUpload upload) {
            return new ProviderFileReference("file_stub");
        }
    }

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        StubEmbeddingStore stubEmbeddingStore() {
            return new StubEmbeddingStore();
        }

        @Bean
        @Primary
        StubAIProvider stubAIProvider() {
            return new StubAIProvider();
        }
    }

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
    @Autowired
    private StubEmbeddingStore stubEmbeddingStore;

    private User alice;
    private User bob;
    private String aliceToken;
    private Chunk aliceChunk;
    private Chunk bobChunk;

    @BeforeEach
    void setUp() {
        chunkRepository.deleteAll();
        documentRepository.deleteAll();
        userRepository.deleteAll();
        stubEmbeddingStore.nextHits = List.of();

        alice = userRepository.save(User.builder()
                .email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        bob = userRepository.save(User.builder()
                .email("bob@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);

        Document aliceDoc = documentRepository.save(Document.builder()
                .userId(alice.getId()).filename("contrat-alice.pdf").mediaType("application/pdf")
                .sizeBytes(10).status(DocumentStatus.INDEXED).ocrMode(OcrMode.ASYNC).chunkCount(1).build());
        aliceChunk = chunkRepository.save(Chunk.builder()
                .documentId(aliceDoc.getId()).userId(alice.getId()).chunkIndex(0)
                .text("Clause de confidentialité d'Alice.").build());

        Document bobDoc = documentRepository.save(Document.builder()
                .userId(bob.getId()).filename("secret-bob.pdf").mediaType("application/pdf")
                .sizeBytes(10).status(DocumentStatus.INDEXED).ocrMode(OcrMode.ASYNC).chunkCount(1).build());
        bobChunk = chunkRepository.save(Chunk.builder()
                .documentId(bobDoc.getId()).userId(bob.getId()).chunkIndex(0)
                .text("Secret confidentiel de Bob.").build());
    }

    private String body(String question) {
        return "{\"question\":\"" + question + "\"}";
    }

    @Test
    void groundedAnswerReturnsCitationsFromOwnDocuments() throws Exception {
        stubEmbeddingStore.nextHits = List.of(new ScoredChunk(aliceChunk.getId(), 0.1));

        mockMvc.perform(post("/api/ask").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Quelle est la clause de confidentialité ?")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", is("Réponse de test.")))
                .andExpect(jsonPath("$.grounded", is(true)))
                .andExpect(jsonPath("$.citations.length()", is(1)))
                .andExpect(jsonPath("$.citations[0].filename", is("contrat-alice.pdf")))
                .andExpect(jsonPath("$.citations[0].chunkIndex", is(0)))
                .andExpect(jsonPath("$.citations[0].snippet", notNullValue()));
    }

    @Test
    void crossUserChunkNeverLeaksThroughReload() throws Exception {
        // Le moteur renvoie (adversarialement) un chunk de Bob ; le rechargement filtré user_id
        // d'Alice l'écarte → repli grounded=false, aucune citation d'un autre utilisateur.
        stubEmbeddingStore.nextHits = List.of(new ScoredChunk(bobChunk.getId(), 0.1));

        mockMvc.perform(post("/api/ask").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Donne-moi le secret de Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded", is(false)))
                .andExpect(jsonPath("$.citations.length()", is(0)));
    }

    @Test
    void noHitsFallsBackWithoutCitations() throws Exception {
        stubEmbeddingStore.nextHits = List.of();

        mockMvc.perform(post("/api/ask").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Une question sans document")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded", is(false)))
                .andExpect(jsonPath("$.citations.length()", is(0)));
    }

    @Test
    void rejectsBlankQuestion() throws Exception {
        mockMvc.perform(post("/api/ask").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }

    @Test
    void rejectsUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/ask").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Une question")))
                .andExpect(status().isUnauthorized());
    }
}
