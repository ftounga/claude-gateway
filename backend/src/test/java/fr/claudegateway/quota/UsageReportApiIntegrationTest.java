package fr.claudegateway.quota;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
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

/**
 * Tests d'intégration du rapport d'usage (SF-16-01) sur {@code GET /api/usage/report} : structure du
 * contrat, rapport vide pour un nouvel utilisateur, exigence d'authentification et isolation
 * {@code user_id} (un utilisateur ne voit jamais les périodes d'autrui). Les compteurs sont
 * persistés directement (aucun appel fournisseur nécessaire).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UsageReportApiIntegrationTest {

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

    private void saveCounter(User user, LocalDate period, long input, long output) {
        usageCounterRepository.save(UsageCounter.builder()
                .userId(user.getId()).periodStart(period)
                .inputTokens(input).outputTokens(output).build());
    }

    @Test
    void reportReturnsHistoryAndTotalsForUserWithCounters() throws Exception {
        saveCounter(alice, LocalDate.of(2026, 6, 1), 1_000, 2_000);
        saveCounter(alice, LocalDate.of(2026, 5, 1), 3_000, 0);

        mockMvc.perform(get("/api/usage/report").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency", is("EUR")))
                .andExpect(jsonPath("$.periods", hasSize(2)))
                // Trié décroissant : juin d'abord.
                .andExpect(jsonPath("$.periods[0].periodStart", is("2026-06-01")))
                .andExpect(jsonPath("$.periods[0].periodEnd", is("2026-07-01")))
                .andExpect(jsonPath("$.periods[0].inputTokens", is(1000)))
                .andExpect(jsonPath("$.periods[0].outputTokens", is(2000)))
                .andExpect(jsonPath("$.periods[0].totalTokens", is(3000)))
                .andExpect(jsonPath("$.periods[0].estimatedCost", notNullValue()))
                .andExpect(jsonPath("$.periods[1].periodStart", is("2026-05-01")))
                .andExpect(jsonPath("$.totalInputTokens", is(4000)))
                .andExpect(jsonPath("$.totalOutputTokens", is(2000)))
                .andExpect(jsonPath("$.totalTokens", is(6000)))
                .andExpect(jsonPath("$.totalEstimatedCost", notNullValue()));
    }

    @Test
    void reportIsEmptyForNewUser() throws Exception {
        mockMvc.perform(get("/api/usage/report").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency", is("EUR")))
                .andExpect(jsonPath("$.periods", hasSize(0)))
                .andExpect(jsonPath("$.totalInputTokens", is(0)))
                .andExpect(jsonPath("$.totalOutputTokens", is(0)))
                .andExpect(jsonPath("$.totalTokens", is(0)));
    }

    @Test
    void reportRejectsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/usage/report").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reportIsIsolatedPerUser() throws Exception {
        saveCounter(alice, LocalDate.of(2026, 6, 1), 5_000, 1_000);

        // Bob ne voit aucune des périodes d'Alice.
        mockMvc.perform(get("/api/usage/report").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periods", hasSize(0)))
                .andExpect(jsonPath("$.totalTokens", is(0)));

        // Alice voit uniquement sa propre période.
        mockMvc.perform(get("/api/usage/report").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periods", hasSize(1)))
                .andExpect(jsonPath("$.totalTokens", is(6000)));
    }
}
