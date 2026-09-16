package fr.claudegateway.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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

/** Le TJM par poste et le mois de départ, de bout en bout (F-124 / SF-124-01). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActivityConfigApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private PosteBillingRepository billingRepository;
    @Autowired private ActivitySettingsRepository settingsRepository;
    @Autowired private JwtService jwtService;

    private User alice;
    private String aliceToken;
    private RunnerHost aliceHost;
    private User bob;
    private String bobToken;
    private RunnerHost bobHost;
    private String noraToken; // USER sans droit Forge

    @BeforeEach
    void setUp() {
        billingRepository.deleteAll();
        settingsRepository.deleteAll();
        workspaceRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        alice = seedUser("alice-activity@example.com", UserRole.ADMIN);
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(alice.getId(), "CAGIP");

        bob = seedUser("bob-activity@example.com", UserRole.ADMIN);
        bobToken = jwtService.generateToken(bob);
        bobHost = seedHost(bob.getId(), "Free");

        User nora = seedUser("nora-activity@example.com", UserRole.USER);
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

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    // -------------------------------------------------------------------- mois de départ

    @Test
    void startMonth_defaultsThenPersists() throws Exception {
        mockMvc.perform(as(get("/api/activity/settings"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startMonth").value("2025-09"));

        mockMvc.perform(as(put("/api/activity/settings"), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startMonth\":\"2026-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startMonth").value("2026-01"));

        mockMvc.perform(as(get("/api/activity/settings"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startMonth").value("2026-01"));
    }

    @Test
    void startMonth_refusesMalformed() throws Exception {
        mockMvc.perform(as(put("/api/activity/settings"), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startMonth\":\"2026-13\"}"))
                .andExpect(status().isBadRequest());
        assertThat(settingsRepository.findAll()).isEmpty();
    }

    // -------------------------------------------------------------------- TJM

    @Test
    void setRate_thenReadIt() throws Exception {
        mockMvc.perform(as(put("/api/activity/rates/" + aliceHost.getId()), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyRateCents\":55000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostId").value(aliceHost.getId().toString()))
                .andExpect(jsonPath("$.dailyRateCents").value(55000));

        mockMvc.perform(as(get("/api/activity/rates"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].dailyRateCents").value(55000));

        // upsert : une seconde écriture met à jour, ne duplique pas
        mockMvc.perform(as(put("/api/activity/rates/" + aliceHost.getId()), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyRateCents\":60000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyRateCents").value(60000));
        assertThat(billingRepository.findByUserId(alice.getId())).hasSize(1);
    }

    @Test
    void setRate_refusesOutOfBounds() throws Exception {
        mockMvc.perform(as(put("/api/activity/rates/" + aliceHost.getId()), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyRateCents\":-1}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(put("/api/activity/rates/" + aliceHost.getId()), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyRateCents\":100000001}"))
                .andExpect(status().isBadRequest());
        assertThat(billingRepository.findAll()).isEmpty();
    }

    @Test
    void deleteRate_isIdempotent() throws Exception {
        mockMvc.perform(as(put("/api/activity/rates/" + aliceHost.getId()), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dailyRateCents\":55000}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(delete("/api/activity/rates/" + aliceHost.getId()), aliceToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/api/activity/rates/" + aliceHost.getId()), aliceToken))
                .andExpect(status().isNoContent());
        assertThat(billingRepository.findAll()).isEmpty();
    }

    // -------------------------------------------------------------------- isolation user_id

    @Test
    void anotherUsersHostIsNotFound_andNothingLeaks() throws Exception {
        // Bob ne peut ni lire ni écrire le TJM du poste d'Alice : 404 indiscernable.
        mockMvc.perform(as(put("/api/activity/rates/" + aliceHost.getId()), bobToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dailyRateCents\":99000}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(delete("/api/activity/rates/" + aliceHost.getId()), bobToken))
                .andExpect(status().isNotFound());
        assertThat(billingRepository.findAll()).isEmpty();
    }

    @Test
    void rateListsAreDisjointBetweenUsers() throws Exception {
        mockMvc.perform(as(put("/api/activity/rates/" + aliceHost.getId()), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dailyRateCents\":55000}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(put("/api/activity/rates/" + bobHost.getId()), bobToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dailyRateCents\":70000}"))
                .andExpect(status().isOk());

        mockMvc.perform(as(get("/api/activity/rates"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].hostId").value(aliceHost.getId().toString()))
                .andExpect(jsonPath("$[0].dailyRateCents").value(55000));

        mockMvc.perform(as(get("/api/activity/rates"), bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].hostId").value(bobHost.getId().toString()));

        // Le mois de départ est aussi par utilisateur : celui de Bob n'affecte pas Alice.
        mockMvc.perform(as(put("/api/activity/settings"), bobToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"startMonth\":\"2024-06\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/api/activity/settings"), aliceToken))
                .andExpect(jsonPath("$.startMonth").value("2025-09"));
    }

    @Test
    void requiresAuthenticationAndForgeRight() throws Exception {
        mockMvc.perform(get("/api/activity/settings").contextPath("/api"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(as(get("/api/activity/settings"), noraToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletingTheHostErasesItsRate() throws Exception {
        mockMvc.perform(as(put("/api/activity/rates/" + aliceHost.getId()), aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dailyRateCents\":55000}"))
                .andExpect(status().isOk());
        assertThat(billingRepository.findByUserId(alice.getId())).hasSize(1);
        // FK ON DELETE CASCADE : le TJM ne survit pas au poste.
        hostRepository.delete(aliceHost);
        hostRepository.flush();
        assertThat(billingRepository.findByUserId(alice.getId())).isEmpty();
    }
}
