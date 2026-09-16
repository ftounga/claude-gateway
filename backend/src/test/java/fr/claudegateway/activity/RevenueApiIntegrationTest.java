package fr.claudegateway.activity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** Le cumul de revenu, de bout en bout (F-124 / SF-124-02). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RevenueApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private PosteBillingRepository billingRepository;
    @Autowired private CraEntryRepository craRepository;
    @Autowired private ActivitySettingsRepository settingsRepository;
    @Autowired private JwtService jwtService;

    private User alice;
    private String aliceToken;
    private RunnerHost aliceHost;
    private String bobToken;
    private String noraToken;

    @BeforeEach
    void setUp() {
        craRepository.deleteAll();
        billingRepository.deleteAll();
        settingsRepository.deleteAll();
        workspaceRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        alice = seedUser("alice-revenue@example.com", UserRole.ADMIN);
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(alice.getId(), "CAGIP");
        rate(alice.getId(), aliceHost.getId(), 55_000L); // 550 €/j

        User bob = seedUser("bob-revenue@example.com", UserRole.ADMIN);
        bobToken = jwtService.generateToken(bob);
        RunnerHost bobHost = seedHost(bob.getId(), "Free");
        rate(bob.getId(), bobHost.getId(), 70_000L);
        cra(bob.getId(), bobHost.getId(), "2025-09", new BigDecimal("20"));

        User nora = seedUser("nora-revenue@example.com", UserRole.USER);
        noraToken = jwtService.generateToken(nora);
    }

    private User seedUser(String email, UserRole role) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build());
    }

    private RunnerHost seedHost(UUID userId, String name) {
        return hostRepository.save(RunnerHost.builder().userId(userId).name(name).rootName("dev")
                .os("linux").shell("posix").elevated(false).build());
    }

    private void rate(UUID userId, UUID hostId, long cents) {
        billingRepository.save(PosteBilling.builder().userId(userId).hostId(hostId)
                .dailyRateCents(cents).build());
    }

    private void cra(UUID userId, UUID hostId, String month, BigDecimal days) {
        craRepository.save(CraEntry.builder().userId(userId).hostId(hostId).yearMonth(month)
                .days(days).build());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    @Test
    void noCra_everythingIsSupposed() throws Exception {
        mockMvc.perform(as(get("/api/activity/revenue"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startMonth").value("2025-09"))
                .andExpect(jsonPath("$.postes.length()").value(1))
                .andExpect(jsonPath("$.postes[0].hostId").value(aliceHost.getId().toString()))
                .andExpect(jsonPath("$.postes[0].tjmCents").value(55000))
                .andExpect(jsonPath("$.totalDeclaredCents").value(0))
                // Des mois passés depuis 2025-09 → une part supposée strictement positive.
                .andExpect(jsonPath("$.totalSupposedCents").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.totalCents").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void aDeclaredMonthShowsAsDeclared() throws Exception {
        cra(alice.getId(), aliceHost.getId(), "2025-09", new BigDecimal("20"));
        mockMvc.perform(as(get("/api/activity/revenue"), aliceToken))
                .andExpect(status().isOk())
                // Septembre 2025 déclaré à 20 j × 550 € = 11 000 € = 1 100 000 centimes.
                .andExpect(jsonPath("$.postes[0].declaredCents").value(1_100_000))
                .andExpect(jsonPath("$.totalDeclaredCents").value(1_100_000));
    }

    @Test
    void cumulsAreIsolatedBetweenUsers() throws Exception {
        // Alice ne voit que son poste, jamais celui de Bob (ni son TJM ni son CRA).
        mockMvc.perform(as(get("/api/activity/revenue"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postes.length()").value(1))
                .andExpect(jsonPath("$.postes[0].hostId").value(aliceHost.getId().toString()));

        // Bob a un CRA déclaré (20 j × 700 €) : sa part déclarée est la sienne, pas celle d'Alice.
        mockMvc.perform(as(get("/api/activity/revenue"), bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postes.length()").value(1))
                .andExpect(jsonPath("$.totalDeclaredCents").value(1_400_000));
    }

    @Test
    void requiresAuthenticationAndForgeRight() throws Exception {
        mockMvc.perform(get("/api/activity/revenue").contextPath("/api"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(as(get("/api/activity/revenue"), noraToken))
                .andExpect(status().isForbidden());
    }
}
