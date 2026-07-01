package fr.claudegateway.byok;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.AIProviderException;
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
 * Tests d'intégration BYOK sur {@code /api/user/api-key} : ajout (validation par appel test),
 * masquage, statut, suppression, cas d'erreur et isolation {@code user_id}. Le vrai fournisseur IA
 * est remplacé par un stub (bean @Primary) afin de valider la clé sans réseau.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ByokKeyApiIntegrationTest {

    /** Stub de fournisseur : accepte la clé par défaut, ou refuse si {@code reject} est vrai. */
    static class StubAIProvider implements AIProvider {
        volatile boolean reject;
        volatile ChatCompletionRequest lastRequest;

        void reset() {
            reject = false;
            lastRequest = null;
        }

        @Override
        public ChatCompletionResult complete(ChatCompletionRequest request) {
            this.lastRequest = request;
            if (reject) {
                throw new AIProviderException("clé refusée");
            }
            return new ChatCompletionResult("ok", request.model(), 1, 1);
        }

        @Override
        public ProviderFileReference uploadFile(ProviderFileUpload upload) {
            return new ProviderFileReference("file_stub");
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
    private UserApiKeyRepository userApiKeyRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private StubAIProvider stubAIProvider;

    private User alice;
    private String aliceToken;
    private User bob;
    private String bobToken;

    @BeforeEach
    void setUp() {
        userApiKeyRepository.deleteAll();
        userRepository.deleteAll();
        stubAIProvider.reset();

        alice = seedUser("alice@example.com");
        aliceToken = jwtService.generateToken(alice);
        bob = seedUser("bob@example.com");
        bobToken = jwtService.generateToken(bob);
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder()
                .email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
    }

    private String body(String apiKey) {
        return "{\"apiKey\":\"" + apiKey + "\"}";
    }

    @Test
    void savesKeyAndReturnsMaskedStatus() throws Exception {
        mockMvc.perform(post("/api/user/api-key").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("sk-ant-api03-secret-AB12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present", is(true)))
                .andExpect(jsonPath("$.last4", is("AB12")))
                .andExpect(jsonPath("$.maskedKey", is("sk-…AB12")))
                .andExpect(jsonPath("$.mode", is("BYOK")))
                .andExpect(jsonPath("$.provider", is("ANTHROPIC")));

        // Persistance chiffrée : jamais la clé en clair en base.
        UserApiKey stored = userApiKeyRepository.findByUserId(alice.getId()).orElseThrow();
        assertThat(stored.getCiphertext()).doesNotContain("sk-ant-api03-secret-AB12");
        assertThat(stored.getKeyLast4()).isEqualTo("AB12");
        assertThat(stored.isActive()).isTrue();
    }

    @Test
    void rejectsBlankKey() throws Exception {
        mockMvc.perform(post("/api/user/api-key").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }

    @Test
    void rejectsInvalidFormat() throws Exception {
        mockMvc.perform(post("/api/user/api-key").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("not-a-key")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("invalid_api_key")));

        assertThat(userApiKeyRepository.findByUserId(alice.getId())).isEmpty();
    }

    @Test
    void rejectsKeyRefusedByProviderWithoutPersisting() throws Exception {
        stubAIProvider.reject = true;

        mockMvc.perform(post("/api/user/api-key").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("sk-ant-would-be-valid-1234")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("invalid_api_key")));

        assertThat(userApiKeyRepository.findByUserId(alice.getId())).isEmpty();
    }

    @Test
    void statusReflectsPresenceAndAbsence() throws Exception {
        // Absente.
        mockMvc.perform(get("/api/user/api-key").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present", is(false)))
                .andExpect(jsonPath("$.mode", is("HOSTED")));

        // Après ajout.
        mockMvc.perform(post("/api/user/api-key").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("sk-ant-secret-CD34")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/user/api-key").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present", is(true)))
                .andExpect(jsonPath("$.last4", is("CD34")));
    }

    @Test
    void deleteRemovesKey() throws Exception {
        mockMvc.perform(post("/api/user/api-key").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("sk-ant-secret-EF56")))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/user/api-key").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        assertThat(userApiKeyRepository.findByUserId(alice.getId())).isEmpty();
    }

    @Test
    void isolatesKeysBetweenUsers() throws Exception {
        // Alice et Bob enregistrent chacun une clé.
        mockMvc.perform(post("/api/user/api-key").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("sk-ant-alice-AAAA")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/user/api-key").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("sk-ant-bob-BBBB")))
                .andExpect(status().isOk());

        // Bob ne voit que sa clé.
        mockMvc.perform(get("/api/user/api-key").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.last4", is("BBBB")));

        // La suppression par Bob n'affecte pas la clé d'Alice.
        mockMvc.perform(delete("/api/user/api-key").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNoContent());
        assertThat(userApiKeyRepository.findByUserId(alice.getId())).isPresent();
        assertThat(userApiKeyRepository.findByUserId(bob.getId())).isEmpty();
    }

    @Test
    void togglesModeBetweenHostedAndByok() throws Exception {
        mockMvc.perform(post("/api/user/api-key").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("sk-ant-secret-GH78")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode", is("BYOK")));

        // Bascule vers HOSTED : la clé est conservée mais inactive.
        mockMvc.perform(put("/api/user/api-key/mode").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"HOSTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present", is(true)))
                .andExpect(jsonPath("$.mode", is("HOSTED")));
        assertThat(userApiKeyRepository.findByUserId(alice.getId()).orElseThrow().isActive()).isFalse();

        // Retour à BYOK.
        mockMvc.perform(put("/api/user/api-key/mode").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"BYOK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode", is("BYOK")));
        assertThat(userApiKeyRepository.findByUserId(alice.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void modeByokWithoutKeyReturnsConflict() throws Exception {
        mockMvc.perform(put("/api/user/api-key/mode").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"BYOK\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("byok_mode_conflict")));
    }

    @Test
    void modeInvalidValueReturnsValidationError() throws Exception {
        mockMvc.perform(put("/api/user/api-key/mode").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"OTHER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }

    @Test
    void modeToggleIsolatedBetweenUsers() throws Exception {
        // Alice enregistre une clé (BYOK actif).
        mockMvc.perform(post("/api/user/api-key").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("sk-ant-alice-AAAA")))
                .andExpect(status().isOk());

        // Bob (sans clé) tente BYOK => 409, sans affecter Alice.
        mockMvc.perform(put("/api/user/api-key/mode").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"BYOK\"}"))
                .andExpect(status().isConflict());
        assertThat(userApiKeyRepository.findByUserId(alice.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void rejectsUnauthenticatedOnAllRoutes() throws Exception {
        mockMvc.perform(get("/api/user/api-key").contextPath("/api"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/user/api-key").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(body("sk-ant-x")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/user/api-key").contextPath("/api"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/user/api-key/mode").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"HOSTED\"}"))
                .andExpect(status().isUnauthorized());
    }
}
