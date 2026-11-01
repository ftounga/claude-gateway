package fr.claudegateway.jarvis.fastbrain;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** F-235 / SF-235-01 (D17) — coupe-circuit par défaut : {@code /api/jarvis/converse} répond 503. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisFastBrainDormantApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    @Test
    @DisplayName("éteint (défaut) : 503, l'écran garde le mode d'avant")
    void dormantByDefault() throws Exception {
        User alice = userRepository.save(User.builder().email("fast-dormant-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        mockMvc.perform(post("/api/jarvis/converse").contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\"" + UUID.randomUUID() + "\",\"text\":\"Bonjour\"}"))
                .andExpect(status().isServiceUnavailable());
        // SF-235-02 : le pré-chauffage est éteint lui aussi.
        mockMvc.perform(post("/api/jarvis/converse/warm").contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("SF-235-08 — le banc : ADMIN seulement, candidats en liste fermée, rapport d'autrui introuvable")
    void benchIsAdminOnlyAndClosed() throws Exception {
        User admin = userRepository.save(User.builder().email("fast-bench-a-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        User user = userRepository.save(User.builder().email("fast-bench-u-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        String adminAuth = "Bearer " + jwtService.generateToken(admin);
        mockMvc.perform(post("/api/admin/jarvis/fast-brain/bench").contextPath("/api")
                        .header("Authorization", adminAuth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-5\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/admin/jarvis/fast-brain/bench/" + UUID.randomUUID()).contextPath("/api")
                        .header("Authorization", adminAuth))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/jarvis/fast-brain/bench").contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }
}
