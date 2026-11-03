package fr.claudegateway.jarvis;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** Le compteur visible de Jarvis (F-239 / SF-239-12) : {@code GET /api/jarvis/costs}, en euros, par compte. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisCostApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JarvisUsageService usage;

    private User user(String prefix) {
        return userRepository.save(User.builder().email(prefix + "-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.ADMIN)
                .createdAt(OffsetDateTime.now()).build());
    }

    @Test
    void theAccountSeesItsOwnCostsInEurosOnly() throws Exception {
        User alice = user("cost-alice");
        User bob = user("cost-bob");
        usage.addOpus(alice.getId(), new BigDecimal("1.06"));
        usage.addFastBrain(alice.getId(), new BigDecimal("0.10"));

        mockMvc.perform(get("/api/jarvis/costs").contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today.opus").value(0.98))
                .andExpect(jsonPath("$.today.opusTurns").value(1))
                .andExpect(jsonPath("$.month.total").value(1.07));
        mockMvc.perform(get("/api/jarvis/costs").contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(bob)))
                .andExpect(jsonPath("$.month.total").value(0.0));
        mockMvc.perform(get("/api/jarvis/costs").contextPath("/api")).andExpect(status().isUnauthorized());
    }

    @Test
    void f244EveryItemTheTotalAsTheirSumAndTheCapGauge() throws Exception {
        // F-244 / SF-244-04 (D10) — six postes en euros, total = somme des postes affichés, plafond et état (jauge).
        User alice = user("cost-f244-alice");
        User bob = user("cost-f244-bob");
        usage.addOpus(alice.getId(), new BigDecimal("1.06"));
        usage.addFastBrain(alice.getId(), new BigDecimal("0.10"));
        usage.addSpeech(alice.getId(), 1_000L, new BigDecimal("0.30"));
        usage.addStream(alice.getId(), 60L, new BigDecimal("0.01"));
        usage.addUtterance(alice.getId(), 2L, new BigDecimal("0.01"));
        usage.addDictation(alice.getId(), 3L, new BigDecimal("0.01"));

        mockMvc.perform(get("/api/jarvis/costs").contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month.fastBrain").value(0.09))
                .andExpect(jsonPath("$.month.opus").value(0.98))
                .andExpect(jsonPath("$.month.voice").value(0.28))
                .andExpect(jsonPath("$.month.listening").value(0.03))
                .andExpect(jsonPath("$.month.mail").value(0.0))
                .andExpect(jsonPath("$.month.agenda").value(0.0))
                .andExpect(jsonPath("$.month.total").value(1.38))
                .andExpect(jsonPath("$.budget.monthlyCapEur").value(150))
                .andExpect(jsonPath("$.budget.state").value("NONE"));
        mockMvc.perform(get("/api/jarvis/costs").contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(bob)))
                .andExpect(jsonPath("$.month.total").value(0.0))
                .andExpect(jsonPath("$.budget.monthSpentEur").value(0.0));
    }
}
