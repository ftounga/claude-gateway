package fr.claudegateway.ocr;

import static org.hamcrest.Matchers.is;
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
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Test d'intégration bout-en-bout du flux OCR asynchrone (SF-05-02) : un PDF soumis via l'API passe
 * en {@code PROCESSING}, puis, après un cycle de polling, devient {@code EXTRACTED} avec son texte,
 * le tout en respectant l'isolation {@code user_id}. Le worker planifié est désactivé (profil test) :
 * on invoque {@link DocumentService#pollPendingJobs()} directement pour un déterminisme total.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OcrAsyncFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private JwtService jwtService;

    private User alice;
    private String aliceToken;

    @BeforeEach
    void setUp() {
        documentRepository.deleteAll();
        userRepository.deleteAll();
        alice = userRepository.save(User.builder()
                .email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);
    }

    @Test
    void pdfMovesFromProcessingToExtractedAfterPolling() throws Exception {
        MockMultipartFile pdf = new MockMultipartFile(
                "file", "contrat.pdf", "application/pdf", new byte[] {1, 2, 3, 4, 5});

        // 1) Soumission : le PDF part en OCR asynchrone (statut PROCESSING).
        String location = mockMvc.perform(multipart("/api/documents").file(pdf).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("PROCESSING")))
                .andReturn().getResponse().getContentAsString();

        java.util.UUID documentId = documentRepository.findAll().get(0).getId();

        // 2) Un cycle de polling : le stub renvoie SUCCEEDED → le document devient EXTRACTED.
        int completed = documentService.pollPendingJobs();
        org.assertj.core.api.Assertions.assertThat(completed).isEqualTo(1);

        // 3) Lecture isolée : Alice voit son document EXTRACTED avec le texte, provider_job_id jamais exposé.
        mockMvc.perform(get("/api/documents/{id}", documentId).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("EXTRACTED")))
                .andExpect(jsonPath("$.extractedText", is(org.hamcrest.Matchers.notNullValue())))
                .andExpect(jsonPath("$.providerJobId").doesNotExist());

        org.assertj.core.api.Assertions.assertThat(location).doesNotContain("providerJobId");
    }
}
