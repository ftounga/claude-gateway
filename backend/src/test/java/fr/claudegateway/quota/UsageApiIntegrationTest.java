package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.ai.AIProvider;
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
 * Tests d'intégration de F-10 sur {@code /api/**} : consultation de la consommation, enforcement du
 * quota sur le chat (blocage 402), isolation {@code user_id}. Quota d'essai abaissé à 30 tokens pour
 * atteindre la limite en quelques appels. Le fournisseur IA réel est remplacé par un stub (20
 * tokens/appel).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.quota.trial-tokens=30")
class UsageApiIntegrationTest {

    /** Stub renvoyant une consommation fixe de 12 + 8 = 20 tokens par appel. */
    static class StubAIProvider implements AIProvider {
        @Override
        public ChatCompletionResult complete(ChatCompletionRequest request) {
            return new ChatCompletionResult("Réponse", request.model(), 12, 8);
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
    private UsageCounterRepository usageCounterRepository;

    @Autowired
    private JwtService jwtService;

    private User alice;
    private String aliceToken;
    private User bob;
    private String bobToken;

    @BeforeEach
    void setUp() {
        usageCounterRepository.deleteAll();
        userRepository.deleteAll();

        alice = userRepository.save(User.builder()
                .email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);

        bob = userRepository.save(User.builder()
                .email("bob@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        bobToken = jwtService.generateToken(bob);
    }

    private void chat(String token) throws Exception {
        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void usageStartsAtZeroForNewUserWithTrialQuota() throws Exception {
        mockMvc.perform(get("/api/usage").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedTokens", is(0)))
                .andExpect(jsonPath("$.quotaTokens", is(30)))
                .andExpect(jsonPath("$.remainingTokens", is(30)))
                .andExpect(jsonPath("$.periodStart", notNullValue()))
                .andExpect(jsonPath("$.periodEnd", notNullValue()));
    }

    @Test
    void usageRejectsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/usage").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void chatConsumesQuotaAndBlocksOnceExceeded() throws Exception {
        // 1er appel : usage 0 < 30 → OK, consomme 20.
        chat(aliceToken);
        mockMvc.perform(get("/api/usage").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.usedTokens", is(20)))
                .andExpect(jsonPath("$.remainingTokens", is(10)));

        // 2e appel : usage 20 < 30 → OK, consomme 20 (total 40, au-delà du quota).
        chat(aliceToken);

        // 3e appel : usage 40 ≥ 30 → 402 quota_exceeded, sans consommation supplémentaire.
        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Encore\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error", is("quota_exceeded")));

        // La consommation n'a pas bougé après le blocage.
        mockMvc.perform(get("/api/usage").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.usedTokens", is(40)));
    }

    @Test
    void usageIsIsolatedPerUser() throws Exception {
        chat(aliceToken); // Alice consomme 20.

        // Bob n'a rien consommé.
        mockMvc.perform(get("/api/usage").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedTokens", is(0)));

        // Alice voit bien sa propre consommation.
        mockMvc.perform(get("/api/usage").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.usedTokens", is(20)));

        assertThat(usageCounterRepository.findAll()).hasSize(1);
        assertThat(usageCounterRepository.findAll().get(0).getUserId()).isEqualTo(alice.getId());
    }
}
