package fr.claudegateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration du profil et de la déconnexion : édition d'e-mail (avec re-vérification),
 * logout simple, et logout-all (invalidation par {@code token_version}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EmailVerificationTokenRepository verificationTokenRepository;
    @Autowired
    private JwtService jwtService;

    private User alice;

    @BeforeEach
    void seed() {
        verificationTokenRepository.deleteAll();
        userRepository.deleteAll();
        alice = userRepository.save(User.builder()
                .email("alice@example.com").passwordHash("HASH").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
    }

    private String tokenFor(User user) {
        return jwtService.generateToken(userRepository.findById(user.getId()).orElseThrow());
    }

    @Test
    void getMeStillWorksWithCurrentToken() throws Exception {
        mockMvc.perform(get("/api/me").contextPath("/api").header("Authorization", "Bearer " + tokenFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("alice@example.com")));
    }

    @Test
    void updateProfileChangesEmailResetsVerificationAndSendsToken() throws Exception {
        mockMvc.perform(put("/api/me").contextPath("/api")
                        .header("Authorization", "Bearer " + tokenFor(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"Alice-New@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("alice-new@example.com")))
                .andExpect(jsonPath("$.emailVerified", is(false)));

        User updated = userRepository.findById(alice.getId()).orElseThrow();
        assertThat(updated.getEmail()).isEqualTo("alice-new@example.com");
        assertThat(updated.isEmailVerified()).isFalse();
        assertThat(verificationTokenRepository.findAll())
                .anyMatch(t -> t.getUserId().equals(alice.getId()));
    }

    @Test
    void updateProfileRejectsEmailAlreadyUsed() throws Exception {
        userRepository.save(User.builder()
                .email("taken@example.com").passwordHash("H").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());

        mockMvc.perform(put("/api/me").contextPath("/api")
                        .header("Authorization", "Bearer " + tokenFor(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"taken@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("email_already_used")));
    }

    @Test
    void updateProfileRejectsInvalidEmail() throws Exception {
        mockMvc.perform(put("/api/me").contextPath("/api")
                        .header("Authorization", "Bearer " + tokenFor(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }

    @Test
    void logoutReturns200() throws Exception {
        mockMvc.perform(post("/api/me/logout").contextPath("/api")
                        .header("Authorization", "Bearer " + tokenFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", is("Déconnecté.")));
    }

    @Test
    void logoutAllInvalidatesPreviousTokens() throws Exception {
        String oldToken = tokenFor(alice);

        // Le token courant fonctionne avant logout-all.
        mockMvc.perform(get("/api/me").contextPath("/api").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/me/logout-all").contextPath("/api")
                        .header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isOk());

        // L'ancien token (tv périmé) est désormais rejeté.
        mockMvc.perform(get("/api/me").contextPath("/api").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());

        // Un nouveau token (tv à jour) fonctionne.
        String newToken = tokenFor(alice);
        mockMvc.perform(get("/api/me").contextPath("/api").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk());
    }
}
