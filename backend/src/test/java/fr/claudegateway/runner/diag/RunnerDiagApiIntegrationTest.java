package fr.claudegateway.runner.diag;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-132 / SF-132-02 — le journal de diagnostic d'un poste, de bout en bout : ce que rend
 * {@code GET /runner-hosts/{hostId}/diag}, ses filtres, et surtout ce qu'il ne rend <b>jamais</b> —
 * le journal du poste d'un autre compte (404).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerDiagApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private RunnerDiagEventRepository diagRepository;
    @Autowired private RunnerDiagService diagService;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    private String aliceToken;
    private String bobToken;
    private String plainToken;
    private UUID aliceHostId;
    private UUID bobHostId;

    @BeforeEach
    void setUp() {
        diagRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-diag@example.com", UserRole.ADMIN);
        aliceToken = jwtService.generateToken(alice);
        aliceHostId = seedHost(alice.getId(), "Poste CAGIP").getId();

        User bob = seedUser("bob-diag@example.com", UserRole.ADMIN);
        bobToken = jwtService.generateToken(bob);
        bobHostId = seedHost(bob.getId(), "Poste de Bob").getId();

        plainToken = jwtService.generateToken(seedUser("plain-diag@example.com", UserRole.USER));
    }

    private User seedUser(String email, UserRole role) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build());
    }

    private RunnerHost seedHost(UUID userId, String name) {
        return hostRepository.save(RunnerHost.builder().userId(userId).name(name).rootName("dev")
                .os("linux").shell("posix").runnerVersion("0.0.1").elevated(false)
                .runnerContract(1).runnerJava(21).runnerLauncher(true).runnerCapabilities("files")
                .lastSeenAt(OffsetDateTime.now()).build());
    }

    private void seedEvent(UUID userId, UUID hostId, String level, String cat, String code,
            OffsetDateTime createdAt) {
        diagRepository.save(RunnerDiagEventEntity.builder().userId(userId).hostId(hostId)
                .level(level).category(cat).code(code).createdAt(createdAt).build());
    }

    @Test
    void ownerSeesHisHostDiagNewestFirst() throws Exception {
        seedEvent(userId("alice-diag@example.com"), aliceHostId, "INFO", "chrome", "chrome_state",
                OffsetDateTime.now().minusMinutes(2));
        seedEvent(userId("alice-diag@example.com"), aliceHostId, "WARN", "teams", "session_state",
                OffsetDateTime.now().minusMinutes(1));

        mockMvc.perform(get("/api/runner-hosts/" + aliceHostId + "/diag").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].code").value("session_state")) // le plus récent d'abord
                .andExpect(jsonPath("$[0].level").value("WARN"))
                .andExpect(jsonPath("$[1].code").value("chrome_state"));
    }

    @Test
    void diagNeverLeaksAnotherAccountsHost() throws Exception {
        seedEvent(userId("bob-diag@example.com"), bobHostId, "INFO", "chrome", "chrome_state",
                OffsetDateTime.now());

        // Alice demande le journal du poste de Bob : indiscernable d'un poste inexistant → 404.
        mockMvc.perform(get("/api/runner-hosts/" + bobHostId + "/diag").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void levelFilterKeepsOnlyAtLeastThatSeverity() throws Exception {
        UUID uid = userId("alice-diag@example.com");
        seedEvent(uid, aliceHostId, "DEBUG", "vigie", "tick", OffsetDateTime.now().minusMinutes(3));
        seedEvent(uid, aliceHostId, "INFO", "chrome", "chrome_state", OffsetDateTime.now().minusMinutes(2));
        seedEvent(uid, aliceHostId, "WARN", "teams", "session_state", OffsetDateTime.now().minusMinutes(1));

        mockMvc.perform(get("/api/runner-hosts/" + aliceHostId + "/diag").contextPath("/api")
                        .param("level", "WARN")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].level").value("WARN"));
    }

    @Test
    void diagIsClosedWithoutRunnerAccess() throws Exception {
        mockMvc.perform(get("/api/runner-hosts/" + aliceHostId + "/diag").contextPath("/api")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void diagIsClosedWithoutJwt() throws Exception {
        mockMvc.perform(get("/api/runner-hosts/" + aliceHostId + "/diag").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ingestedFrameIsReadableByTheOwnerAndExpurgated() throws Exception {
        UUID uid = userId("alice-diag@example.com");
        // Réception d'une trame runner_diag (comme le dispatcher la publie), identité de la session.
        diagService.ingest(uid, aliceHostId, objectMapper.readTree("""
                {"type":"runner_diag","events":[
                  {"ts":"2026-09-19T10:00:00Z","level":"INFO","cat":"capture","code":"stop",
                   "fields":{"result":"ok","bytes":1600,"images":3}}
                ]}"""));

        mockMvc.perform(get("/api/runner-hosts/" + aliceHostId + "/diag").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].category").value("capture"))
                .andExpect(jsonPath("$[0].code").value("stop"))
                .andExpect(jsonPath("$[0].fields.bytes").value(1600))
                .andExpect(jsonPath("$[0].fields.images").value(3));
    }

    private UUID userId(String email) {
        return userRepository.findByEmail(email).orElseThrow().getId();
    }
}
