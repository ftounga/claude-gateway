package fr.claudegateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;

/**
 * Tests d'intégration du flux de réinitialisation : anti-énumération de forgot, reset effectif
 * (login avec le nouveau mot de passe, ancien invalidé) et rejet des tokens invalides.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @BeforeEach
    void clean() {
        tokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    private MockHttpServletRequestBuilder json(String path, String body) {
        return post(path).contextPath("/api").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void register(String email, String password) throws Exception {
        mockMvc.perform(json("/api/auth/register",
                        "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void forgotCreatesTokenForExistingLocalUser() throws Exception {
        register("reset-me@example.com", "password123");

        mockMvc.perform(json("/api/auth/password/forgot", "{\"email\":\"Reset-Me@example.com\"}"))
                .andExpect(status().isOk());

        User user = userRepository.findByEmail("reset-me@example.com").orElseThrow();
        assertThat(tokenRepository.findAll()).anyMatch(t -> t.getUserId().equals(user.getId()));
    }

    @Test
    void forgotIsSilentForUnknownEmail() throws Exception {
        mockMvc.perform(json("/api/auth/password/forgot", "{\"email\":\"nobody@example.com\"}"))
                .andExpect(status().isOk());

        // Aucun token ne doit être créé pour un email inconnu (anti-énumération).
        assertThat(tokenRepository.findAll()).isEmpty();
    }

    @Test
    void resetChangesPasswordAndInvalidatesOldOne() throws Exception {
        register("change@example.com", "old-password1");
        // Déclenche la création d'un token.
        mockMvc.perform(json("/api/auth/password/forgot", "{\"email\":\"change@example.com\"}"))
                .andExpect(status().isOk());
        String token = tokenRepository.findAll().get(0).getToken();

        mockMvc.perform(json("/api/auth/password/reset",
                        "{\"token\":\"" + token + "\",\"password\":\"new-password1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", is("Mot de passe réinitialisé.")));

        // Nouveau mot de passe accepté, ancien refusé.
        mockMvc.perform(json("/api/auth/login",
                        "{\"email\":\"change@example.com\",\"password\":\"new-password1\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(json("/api/auth/login",
                        "{\"email\":\"change@example.com\",\"password\":\"old-password1\"}"))
                .andExpect(status().isUnauthorized());

        // Le token est consommé : un second usage échoue.
        mockMvc.perform(json("/api/auth/password/reset",
                        "{\"token\":\"" + token + "\",\"password\":\"another-pass1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("invalid_token")));
    }

    @Test
    void resetRejectsUnknownToken() throws Exception {
        mockMvc.perform(json("/api/auth/password/reset",
                        "{\"token\":\"bogus\",\"password\":\"new-password1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("invalid_token")));
    }

    @Test
    void resetRejectsShortPassword() throws Exception {
        mockMvc.perform(json("/api/auth/password/reset",
                        "{\"token\":\"whatever\",\"password\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }
}
