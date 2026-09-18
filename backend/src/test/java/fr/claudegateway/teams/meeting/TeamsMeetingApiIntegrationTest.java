package fr.claudegateway.teams.meeting;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.runner.host.ClientSpace;
import fr.claudegateway.runner.host.HostSpace;
import fr.claudegateway.runner.host.HostSpaceRepository;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration des API Réunions de la Vigie (F-128 / SF-128-01) : garde d'accès (droit Teams
 * 403, hors Vigie 409), création nominale, refus de validation, isolation user_id+host_id (404),
 * transitions d'état. Le runner est simulé ({@link RunnerToolGateway} mocké) : la capture réelle
 * (SF-128-02) n'est pas requise ici.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeamsMeetingApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private HostSpaceRepository hostSpaces;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private JwtService jwtService;

    @MockBean private RunnerToolGateway runnerToolGateway;

    private final ObjectMapper mapper = new ObjectMapper();

    private String aliceToken;
    private UUID aliceHost;      // possédé + Vigie active + droit Teams
    private UUID aliceHostNoVigie; // possédé, mais pas activé dans la Vigie

    private String bobToken;     // autre compte (droit Teams), pour l'isolation

    private String carolToken;   // Vigie active mais SANS droit Teams
    private UUID carolHost;

    @BeforeEach
    void setUp() {
        hostSpaces.deleteAll();
        hostRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        SecurityContextHolder.clearContext();

        when(runnerToolGateway.teamsRead(any(), anyString(), anyString(), any()))
                .thenReturn(new RunnerCallResult(true, "cap-ok", false, null, 0L, null, null, null, "", false));

        User alice = seedUser("alice-meetings@example.com");
        entitleTeams(alice.getId());
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(alice.getId(), true);
        aliceHostNoVigie = seedHost(alice.getId(), false);

        User bob = seedUser("bob-meetings@example.com");
        entitleTeams(bob.getId());
        bobToken = jwtService.generateToken(bob);
        seedHost(bob.getId(), true);

        User carol = seedUser("carol-meetings@example.com"); // aucune souscription : pas de droit Teams
        carolToken = jwtService.generateToken(carol);
        carolHost = seedHost(carol.getId(), true);
    }

    // ---------------------------------------------------------------- nominal

    @Test
    @DisplayName("Rejoindre & capturer : 201, état RECORDING, rétention par défaut 30")
    void create_nominal() throws Exception {
        mockMvc.perform(post(url(aliceHost, "")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType("application/json")
                        .content(body("https://teams.microsoft.com/l/meetup-join/abc", true, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RECORDING"))
                .andExpect(jsonPath("$.retentionDays").value(30))
                .andExpect(jsonPath("$.consentAcknowledged").value(true));
    }

    @Test
    @DisplayName("Liste et détail : filtrés sur le poste courant")
    void listAndGet() throws Exception {
        UUID id = created();
        mockMvc.perform(get(url(aliceHost, "")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()));
        mockMvc.perform(get(url(aliceHost, "/" + id)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    // ---------------------------------------------------------------- validation

    @Test
    @DisplayName("Sans consentement : 400 invalid_meeting")
    void create_withoutConsent() throws Exception {
        mockMvc.perform(post(url(aliceHost, "")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType("application/json")
                        .content(body("https://teams.microsoft.com/x", false, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_meeting"));
    }

    @Test
    @DisplayName("URL non http(s) : 400")
    void create_badUrl() throws Exception {
        mockMvc.perform(post(url(aliceHost, "")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType("application/json")
                        .content(body("nope", true, null)))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- garde d'accès

    @Test
    @DisplayName("Sans droit Teams : 403 teams_forbidden")
    void create_withoutTeamsRight() throws Exception {
        mockMvc.perform(post(url(carolHost, "")).contextPath("/api")
                        .header("Authorization", "Bearer " + carolToken)
                        .contentType("application/json")
                        .content(body("https://teams.microsoft.com/x", true, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("teams_forbidden"));
    }

    @Test
    @DisplayName("Poste non activé dans la Vigie : 409 host_not_in_space")
    void create_hostNotInVigie() throws Exception {
        mockMvc.perform(post(url(aliceHostNoVigie, "")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType("application/json")
                        .content(body("https://teams.microsoft.com/x", true, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("host_not_in_space"));
    }

    // ---------------------------------------------------------------- isolation

    @Test
    @DisplayName("ISOLATION : Bob ne voit pas la réunion créée par Alice sur le poste d'Alice (404)")
    void isolation_betweenUsers() throws Exception {
        UUID id = created();
        // Bob (autre compte) sur le poste d'Alice : poste non possédé -> 404
        mockMvc.perform(get(url(aliceHost, "/" + id)).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        // Alice sur un id inconnu -> 404
        mockMvc.perform(get(url(aliceHost, "/" + UUID.randomUUID())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- transitions

    @Test
    @DisplayName("Arrêt : 200 STOPPED puis 409 au second arrêt")
    void stop_thenConflict() throws Exception {
        UUID id = created();
        mockMvc.perform(post(url(aliceHost, "/" + id + "/stop")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STOPPED"));
        mockMvc.perform(post(url(aliceHost, "/" + id + "/stop")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("invalid_state"));
    }

    // ---------------------------------------------------------------- montage

    private UUID created() throws Exception {
        MvcResult result = mockMvc.perform(post(url(aliceHost, "")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType("application/json")
                        .content(body("https://teams.microsoft.com/l/meetup-join/abc", true, null)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).path("id").asText());
    }

    private String url(UUID hostId, String suffix) {
        return "/api/vigie/hosts/" + hostId + "/meetings" + suffix;
    }

    private String body(String url, boolean consent, Integer retention) throws Exception {
        var node = mapper.createObjectNode();
        node.put("meetingUrl", url);
        node.put("consentAcknowledged", consent);
        if (retention != null) {
            node.put("retentionDays", retention);
        }
        return mapper.writeValueAsString(node);
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
    }

    /** Droit Teams (= droit Vigie) souscrit pour de vrai : PRO + option Teams active. */
    private void entitleTeams(UUID userId) {
        subscriptionRepository.save(Subscription.builder()
                .userId(userId)
                .planCode(PlanCode.PRO)
                .status(SubscriptionStatus.ACTIVE)
                .teamsOptionStatus(SubscriptionStatus.ACTIVE)
                .build());
    }

    private UUID seedHost(UUID userId, boolean activateVigie) {
        UUID hostId = hostRepository.save(RunnerHost.builder().userId(userId).name("Poste").build()).getId();
        if (activateVigie) {
            hostSpaces.save(HostSpace.builder().userId(userId).hostId(hostId)
                    .space(ClientSpace.VIGIE).activatedAt(OffsetDateTime.now()).build());
        }
        return hostId;
    }
}
