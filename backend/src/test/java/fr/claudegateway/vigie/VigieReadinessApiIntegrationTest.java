package fr.claudegateway.vigie;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.runner.RunnerToken;
import fr.claudegateway.runner.RunnerTokenRepository;
import fr.claudegateway.runner.RunnerTokenService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>La mise en service guidée de la Vigie</b> (F-122 / SF-122-02), de bout en bout : la check-list se
 * lit pour son propriétaire, jamais pour un autre compte (isolation), et le runner y rapporte son
 * instantané sous son propre jeton (D9).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VigieReadinessApiIntegrationTest {

    private static final String RUNNER_URL = "/api/runner/vigie/readiness";
    private static final String TOKEN_HEADER = "X-Runner-Token";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private RunnerTokenRepository runnerTokenRepository;
    @Autowired private RunnerTokenService tokenService;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private JwtService jwtService;

    private String veraJwt;
    private UUID veraHostId;
    private String veraRunnerToken;

    private String bobJwt;

    @BeforeEach
    void setUp() {
        runnerTokenRepository.deleteAll();
        hostRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        SecurityContextHolder.clearContext();

        User vera = seedUser("vera-readiness@example.com");
        subscribe(vera, PlanCode.GOLD_VIGIE);
        veraJwt = jwtService.generateToken(vera);
        RunnerHost veraHost = hostRepository.save(
                RunnerHost.builder().userId(vera.getId()).name("EDENRED").build());
        veraHostId = veraHost.getId();
        veraRunnerToken = tokenService.issue(vera.getId(), veraHostId, "poste-vera").clearToken();

        User bob = seedUser("bob-readiness@example.com");
        subscribe(bob, PlanCode.GOLD_VIGIE);
        bobJwt = jwtService.generateToken(bob);
    }

    // ---------------------------------------------------------------- lecture propriétaire

    @Test
    @DisplayName("le propriétaire lit une check-list de 4 vérifications, démarrage bloqué au départ")
    void ownerReadsTheChecklist() throws Exception {
        mockMvc.perform(get("/api/runner-hosts/" + veraHostId + "/vigie/readiness")
                        .contextPath("/api").header("Authorization", "Bearer " + veraJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks", Matchers.hasSize(4)))
                .andExpect(jsonPath("$.canStart").value(false));
    }

    // ---------------------------------------------------------------- isolation

    @Test
    @DisplayName("ISOLATION : le poste d'un autre compte est introuvable")
    void anotherAccountsHostIsNotFound() throws Exception {
        mockMvc.perform(get("/api/runner-hosts/" + veraHostId + "/vigie/readiness")
                        .contextPath("/api").header("Authorization", "Bearer " + bobJwt))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- ingestion runner

    @Test
    @DisplayName("sans jeton runner, l'ingestion est refusée en 401")
    void reportWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post(RUNNER_URL).contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chromeReachable\":true,\"teamsConnected\":true,"
                                + "\"teamsSignInRequired\":false,\"teamsReadTest\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("le runner rapporte, et la lecture propriétaire le reflète")
    void reportedSnapshotIsReflectedToOwner() throws Exception {
        mockMvc.perform(post(RUNNER_URL).contextPath("/api")
                        .header(TOKEN_HEADER, veraRunnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chromeReachable\":true,\"teamsConnected\":false,"
                                + "\"teamsSignInRequired\":true,\"teamsReadTest\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/runner-hosts/" + veraHostId + "/vigie/readiness")
                        .contextPath("/api").header("Authorization", "Bearer " + veraJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamsSignInRequired").value(true))
                .andExpect(jsonPath("$.checks[?(@.check=='CHROME_REACHABLE')].status")
                        .value(Matchers.hasItem("OK")))
                .andExpect(jsonPath("$.checks[?(@.check=='TEAMS_CONNECTED')].status")
                        .value(Matchers.hasItem("KO")));
    }

    @Test
    @DisplayName("runner vivant + tout au vert : le démarrage est débloqué")
    void everythingGreenUnlocksStart() throws Exception {
        markRunnerAlive();
        mockMvc.perform(post(RUNNER_URL).contextPath("/api")
                        .header(TOKEN_HEADER, veraRunnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chromeReachable\":true,\"teamsConnected\":true,"
                                + "\"teamsSignInRequired\":false,\"teamsReadTest\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/runner-hosts/" + veraHostId + "/vigie/readiness")
                        .contextPath("/api").header("Authorization", "Bearer " + veraJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canStart").value(true))
                .andExpect(jsonPath("$.checks[?(@.check=='RUNNER_CONNECTED')].status")
                        .value(Matchers.hasItem("OK")));
    }

    @Test
    @DisplayName("aucun AuthenticatedUser n'est posé par l'ingestion runner (D9)")
    void runnerIngestNeverSetsUserPrincipal() throws Exception {
        mockMvc.perform(post(RUNNER_URL).contextPath("/api")
                        .header(TOKEN_HEADER, veraRunnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chromeReachable\":true,\"teamsConnected\":true,"
                                + "\"teamsSignInRequired\":false,\"teamsReadTest\":true}"))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions
                .assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNull();
    }

    // ---------------------------------------------------------------- montage

    private void markRunnerAlive() {
        RunnerToken token = runnerTokenRepository.findAll().stream()
                .filter(t -> veraHostId.equals(t.getHostId())).findFirst().orElseThrow();
        token.setLastSeenAt(OffsetDateTime.now());
        runnerTokenRepository.save(token);
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
    }

    private void subscribe(User user, PlanCode plan) {
        subscriptionRepository.save(Subscription.builder()
                .userId(user.getId())
                .planCode(plan)
                .status(SubscriptionStatus.ACTIVE)
                .build());
    }
}
