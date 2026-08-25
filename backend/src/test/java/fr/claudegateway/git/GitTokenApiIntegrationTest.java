package fr.claudegateway.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration du jeton GitHub sur {@code /api/user/git-token} (F-31 / SF-31-01) :
 * enregistrement (vérification puis chiffrement), masquage, remplacement, retrait, cas d'erreur et
 * isolation {@code user_id}. GitHub est remplacé par un stub (bean {@code @Primary}) : aucun appel
 * réseau.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GitTokenApiIntegrationTest {

    @TestConfiguration
    static class StubGitHubConfig {
        @Bean
        @Primary
        StubGitHubClient stubGitHubClient() {
            return new StubGitHubClient();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserGitCredentialRepository credentialRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private StubGitHubClient stubGitHubClient;

    private User alice;
    private String aliceToken;
    private User bob;
    private String bobToken;

    @BeforeEach
    void setUp() {
        credentialRepository.deleteAll();
        userRepository.deleteAll();
        stubGitHubClient.reset();

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

    private static String body(String token) {
        return "{\"token\":\"" + token + "\"}";
    }

    @Test
    void savesVerifiedTokenAndReturnsMaskedStatus() throws Exception {
        String pat = "github_pat_11ABCDE_secretAB12";

        mockMvc.perform(post("/api/user/git-token").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(pat)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present", is(true)))
                .andExpect(jsonPath("$.githubLogin", is("octocat")))
                .andExpect(jsonPath("$.last4", is("AB12")))
                .andExpect(jsonPath("$.maskedToken", is("…AB12")));

        // Persistance chiffrée : jamais le jeton en clair en base.
        UserGitCredential stored = credentialRepository.findByUserId(alice.getId()).orElseThrow();
        assertThat(stored.getCiphertext()).doesNotContain(pat);
        assertThat(stored.getEncryptedDataKey()).doesNotContain(pat);
        assertThat(stored.getTokenLast4()).isEqualTo("AB12");
        assertThat(stored.getGithubLogin()).isEqualTo("octocat");
    }

    @Test
    void responseNeverContainsThePlainToken() throws Exception {
        String pat = "github_pat_11ABCDE_secretXY99";

        String response = mockMvc.perform(post("/api/user/git-token").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(pat)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(pat);
    }

    @Test
    void rejectsBlankToken() throws Exception {
        mockMvc.perform(post("/api/user/git-token").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));

        assertThat(credentialRepository.findByUserId(alice.getId())).isEmpty();
    }

    @Test
    void rejectsTokenRefusedByGitHubWithoutPersisting() throws Exception {
        stubGitHubClient.reject = true;

        mockMvc.perform(post("/api/user/git-token").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("github_pat_revoked_0000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("invalid_git_token")));

        assertThat(credentialRepository.findByUserId(alice.getId())).isEmpty();
    }

    @Test
    void returnsServiceUnavailableWhenGitHubIsDownAndKeepsPreviousTokenIntact() throws Exception {
        mockMvc.perform(post("/api/user/git-token").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("github_pat_first_1111")))
                .andExpect(status().isOk());

        stubGitHubClient.unavailable = true;

        mockMvc.perform(post("/api/user/git-token").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("github_pat_second_2222")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", is("github_unavailable")));

        // Le jeton précédent reste intact : l'échec temporaire n'a rien écrasé.
        assertThat(credentialRepository.findByUserId(alice.getId()).orElseThrow().getTokenLast4())
                .isEqualTo("1111");
    }

    @Test
    void statusReflectsAbsenceThenPresenceThenRemoval() throws Exception {
        mockMvc.perform(get("/api/user/git-token").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present", is(false)));

        mockMvc.perform(post("/api/user/git-token").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("github_pat_secret_CD34")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/user/git-token").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present", is(true)))
                .andExpect(jsonPath("$.last4", is("CD34")));

        mockMvc.perform(delete("/api/user/git-token").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/user/git-token").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present", is(false)));
    }

    @Test
    void deleteIsIdempotent() throws Exception {
        mockMvc.perform(delete("/api/user/git-token").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/user/git-token").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void replacingTokenKeepsASingleRowPerUser() throws Exception {
        mockMvc.perform(post("/api/user/git-token").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("github_pat_first_1111")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/user/git-token").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("github_pat_second_2222")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.last4", is("2222")));

        assertThat(credentialRepository.findAll()).hasSize(1);
    }

    @Test
    void isolatesTokensBetweenUsers() throws Exception {
        mockMvc.perform(post("/api/user/git-token").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("github_pat_bob_BBBB")))
                .andExpect(status().isOk());

        // Alice ne voit pas le jeton de Bob.
        mockMvc.perform(get("/api/user/git-token").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present", is(false)));

        // Et sa suppression ne touche pas celui de Bob.
        mockMvc.perform(delete("/api/user/git-token").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        assertThat(credentialRepository.findByUserId(bob.getId())).isPresent();
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/user/git-token").contextPath("/api"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/user/git-token").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("github_pat_anon_0000")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/user/git-token").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }
}
