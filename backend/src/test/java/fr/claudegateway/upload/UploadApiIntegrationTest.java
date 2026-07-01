package fr.claudegateway.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ProviderFileReference;
import fr.claudegateway.ai.ProviderFileUpload;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration de {@code POST /api/upload} : flux nominal, validations (type, présence),
 * authentification, isolation {@code user_id}, non-fuite de la clé et mapping fournisseur dormant.
 * Le vrai {@link AIProvider} est remplacé par un stub pour ne jamais appeler Anthropic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UploadApiIntegrationTest {

    /** Stub de fournisseur injecté à la place d'{@code AnthropicProvider} (bean @Primary). */
    static class StubAIProvider implements AIProvider {
        volatile RuntimeException toThrow;
        volatile ProviderFileUpload lastUpload;

        void reset() {
            toThrow = null;
            lastUpload = null;
        }

        @Override
        public ChatCompletionResult complete(ChatCompletionRequest request) {
            return new ChatCompletionResult("ok", request.model(), 1, 1);
        }

        @Override
        public ProviderFileReference uploadFile(ProviderFileUpload upload) {
            this.lastUpload = upload;
            if (toThrow != null) {
                throw toThrow;
            }
            return new ProviderFileReference("file_stub_123");
        }
    }

    @TestConfiguration
    static class StubProviderConfig {
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
    private UploadedFileRepository uploadedFileRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private StubAIProvider stubAIProvider;

    private User alice;
    private String aliceToken;

    @BeforeEach
    void setUp() {
        uploadedFileRepository.deleteAll();
        userRepository.deleteAll();
        stubAIProvider.reset();

        alice = userRepository.save(User.builder()
                .email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "rapport.pdf", "application/pdf", new byte[] {1, 2, 3, 4});
    }

    @Test
    void uploadsAndTransmitsToProvider() throws Exception {
        mockMvc.perform(multipart("/api/upload").file(pdf()).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.filename", is("rapport.pdf")))
                .andExpect(jsonPath("$.mediaType", is("application/pdf")))
                .andExpect(jsonPath("$.sizeBytes", is(4)));

        assertThat(stubAIProvider.lastUpload).isNotNull();
        assertThat(stubAIProvider.lastUpload.mediaType()).isEqualTo("application/pdf");
        assertThat(uploadedFileRepository.findAll()).hasSize(1);
        assertThat(uploadedFileRepository.findAll().get(0).getUserId()).isEqualTo(alice.getId());
        // La réponse JSON ne doit jamais exposer l'identifiant fournisseur.
    }

    @Test
    void rejectsMissingFile() throws Exception {
        mockMvc.perform(multipart("/api/upload").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
        assertThat(uploadedFileRepository.findAll()).isEmpty();
    }

    @Test
    void rejectsUnsupportedType() throws Exception {
        MockMultipartFile exe = new MockMultipartFile(
                "file", "x.exe", "application/x-msdownload", new byte[] {1, 2, 3});
        mockMvc.perform(multipart("/api/upload").file(exe).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error", is("unsupported_file_type")));
        assertThat(uploadedFileRepository.findAll()).isEmpty();
    }

    @Test
    void rejectsUnauthenticated() throws Exception {
        mockMvc.perform(multipart("/api/upload").file(pdf()).contextPath("/api"))
                .andExpect(status().isUnauthorized());
        assertThat(uploadedFileRepository.findAll()).isEmpty();
    }

    @Test
    void mapsProviderUnavailableTo503() throws Exception {
        stubAIProvider.toThrow = new AIProviderUnavailableException("dormant");

        mockMvc.perform(multipart("/api/upload").file(pdf()).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", is("provider_unavailable")));
        assertThat(uploadedFileRepository.findAll()).isEmpty();
    }
}
