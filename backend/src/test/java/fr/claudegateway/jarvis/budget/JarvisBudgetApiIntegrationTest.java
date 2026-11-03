package fr.claudegateway.jarvis.budget;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** Le plafond de Jarvis par l'API (F-244 / SF-244-03) : état, réglage, « oui », isolation A/B. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisBudgetApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwtService;
    @Autowired private JarvisUsageService usage;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        alice = users.save(user("jarvis-budget-a-"));
        bob = users.save(user("jarvis-budget-b-"));
    }

    private static User user(String prefix) {
        return User.builder().email(prefix + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).createdAt(OffsetDateTime.now()).build();
    }

    private String token(User user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    @Test
    @DisplayName("défaut 150 €, réglage immédiat, état SOFT au-delà ; B ne voit ni ne change le plafond de A")
    void readUpdateAndIsolation() throws Exception {
        usage.addOpus(alice.getId(), new BigDecimal("60")); // 60 $ ≈ 55,20 €
        mockMvc.perform(get("/api/jarvis/budget").contextPath("/api").header("Authorization", token(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyCapEur").value(150))
                .andExpect(jsonPath("$.state").value("NONE"));

        mockMvc.perform(put("/api/jarvis/budget").contextPath("/api").header("Authorization", token(alice))
                .contentType(MediaType.APPLICATION_JSON).content("{\"monthlyCapEur\": 50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyCapEur").value(50))
                .andExpect(jsonPath("$.state").value("SOFT"));

        mockMvc.perform(get("/api/jarvis/budget").contextPath("/api").header("Authorization", token(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyCapEur").value(150))
                .andExpect(jsonPath("$.monthSpentEur").value(0))
                .andExpect(jsonPath("$.state").value("NONE"));
        mockMvc.perform(put("/api/jarvis/budget").contextPath("/api").header("Authorization", token(bob))
                .contentType(MediaType.APPLICATION_JSON).content("{\"monthlyCapEur\": 999}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/jarvis/budget").contextPath("/api").header("Authorization", token(alice)))
                .andExpect(jsonPath("$.monthlyCapEur").value(50));
    }

    @Test
    @DisplayName("400 hors bornes ; 204 sur le « oui » ; 401 sans jeton")
    void errorsAndConfirm() throws Exception {
        mockMvc.perform(put("/api/jarvis/budget").contextPath("/api").header("Authorization", token(alice))
                .contentType(MediaType.APPLICATION_JSON).content("{\"monthlyCapEur\": -5}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/jarvis/budget/confirm").contextPath("/api").header("Authorization", token(alice)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/jarvis/budget").contextPath("/api")).andExpect(status().isUnauthorized());
    }
}
