package fr.claudegateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;

/**
 * Tests d'intégration de la vérification d'e-mail : l'inscription crée un token, la confirmation
 * bascule {@code email_verified}, et un token invalide est rejeté (400).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailVerificationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EmailVerificationTokenRepository tokenRepository;

    @BeforeEach
    void clean() {
        tokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/auth/register").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void registerCreatesVerificationTokenForNewUser() throws Exception {
        register("verify-me@example.com");

        User user = userRepository.findByEmail("verify-me@example.com").orElseThrow();
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(tokenRepository.findAll())
                .anyMatch(t -> t.getUserId().equals(user.getId()) && t.getUsedAt() == null);
    }

    @Test
    void verifyWithValidTokenMarksEmailVerified() throws Exception {
        register("confirm@example.com");
        User user = userRepository.findByEmail("confirm@example.com").orElseThrow();
        String token = tokenRepository.findAll().stream()
                .filter(t -> t.getUserId().equals(user.getId()))
                .findFirst().orElseThrow().getToken();

        mockMvc.perform(get("/api/auth/verify").contextPath("/api").param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified", is(true)))
                .andExpect(jsonPath("$.email", is("confirm@example.com")));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isEmailVerified()).isTrue();
    }

    @Test
    void verifyWithUnknownTokenReturns400() throws Exception {
        mockMvc.perform(get("/api/auth/verify").contextPath("/api").param("token", "bogus-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("invalid_token")));
    }

    @Test
    void verifyWithoutTokenParamReturns400() throws Exception {
        mockMvc.perform(get("/api/auth/verify").contextPath("/api"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }
}
