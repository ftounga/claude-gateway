package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
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

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Intégration de la consommation par client (F-61 / SF-61-02) : forme de la réponse, réconciliation
 * des totaux, refus de fenêtre, et <b>isolation</b> — deux comptes, deux postes homonymes.
 *
 * <p>Le test central est {@code totalsNeverShrink} : c'est le scénario redouté par le cadrage — le
 * compteur de session du workspace est remis à zéro, et le relevé par client, lui, continue de
 * croître.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UsageByClientApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RunnerHostRepository hostRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private UsageTurnRepository usageTurnRepository;
    @Autowired
    private JwtService jwtService;

    private UUID aliceId;
    private UUID bobId;
    private String aliceToken;
    private String bobToken;
    private UUID aliceHost;
    private UUID aliceProject;

    @BeforeEach
    void setUp() {
        usageTurnRepository.deleteAll();
        workspaceRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        User alice = userRepository.save(User.builder()
                .email("alice-byclient@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        User bob = userRepository.save(User.builder()
                .email("bob-byclient@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceId = alice.getId();
        bobId = bob.getId();
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);

        aliceHost = hostRepository.save(host(aliceId, "poste-groupe-x")).getId();
        aliceProject = workspaceRepository.save(workspace(aliceId, "refonte-paie")).getId();
    }

    private RunnerHost host(UUID userId, String name) {
        return RunnerHost.builder().userId(userId).name(name)
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build();
    }

    private Workspace workspace(UUID userId, String name) {
        return Workspace.builder().userId(userId).name(name).build();
    }

    private void turn(UUID userId, UUID workspaceId, UUID hostId, long input, long output) {
        usageTurnRepository.save(UsageTurn.builder()
                .userId(userId).workspaceId(workspaceId).hostId(hostId)
                .inputTokens(input).outputTokens(output)
                .occurredAt(OffsetDateTime.now()).build());
    }

    @Test
    void returnsClientsWithTheirProjects() throws Exception {
        turn(aliceId, aliceProject, aliceHost, 900_000L, 180_000L);

        mockMvc.perform(get("/api/usage/by-client").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.inputTokens").value(900_000))
                .andExpect(jsonPath("$.outputTokens").value(180_000))
                .andExpect(jsonPath("$.clients[0].hostName").value("poste-groupe-x"))
                .andExpect(jsonPath("$.clients[0].projects[0].name").value("refonte-paie"))
                .andExpect(jsonPath("$.clients[0].share").value(1.0));
    }

    @Test
    void totalsNeverShrink() throws Exception {
        // Le scénario du cadrage §1 : trois tours successifs, et entre eux la remise à zéro du
        // compteur de SESSION du workspace. Le relevé par client, lui, ne fait que croître.
        turn(aliceId, aliceProject, aliceHost, 10_000L, 1_000L);
        Workspace project = workspaceRepository.findById(aliceProject).orElseThrow();
        project.setAgentInputTokens(10_000L);
        project.setAgentOutputTokens(1_000L);
        workspaceRepository.save(project);

        mockMvc.perform(get("/api/usage/by-client").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.totalTokens").value(11_000));

        // Nouvelle session : le compteur du workspace repart de zéro (markSessionOpened).
        project.setAgentInputTokens(0L);
        project.setAgentOutputTokens(0L);
        workspaceRepository.save(project);
        turn(aliceId, aliceProject, aliceHost, 5_000L, 500L);

        mockMvc.perform(get("/api/usage/by-client").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTokens").value(16_500))
                .andExpect(jsonPath("$.clients[0].totalTokens").value(16_500));
    }

    @Test
    void turnsWithoutHostAreCountedInTheOutsideBucket() throws Exception {
        turn(aliceId, aliceProject, aliceHost, 10_000L, 0L);
        turn(aliceId, null, null, 4_000L, 0L);

        mockMvc.perform(get("/api/usage/by-client").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTokens").value(14_000))
                .andExpect(jsonPath("$.clients.length()").value(2))
                // Le seau « hors client » vient en dernier, sans nom de poste.
                .andExpect(jsonPath("$.clients[1].hostId").doesNotExist())
                .andExpect(jsonPath("$.clients[1].totalTokens").value(4_000));
    }

    @Test
    void oneAccountNeverSeesAnother() throws Exception {
        // Deux postes portant le MÊME nom, un par compte : seul le sien doit apparaître.
        UUID bobHost = hostRepository.save(host(bobId, "poste-groupe-x")).getId();
        UUID bobProject = workspaceRepository.save(workspace(bobId, "refonte-paie")).getId();
        turn(aliceId, aliceProject, aliceHost, 1_000L, 0L);
        turn(bobId, bobProject, bobHost, 9_999_999L, 0L);

        mockMvc.perform(get("/api/usage/by-client").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTokens").value(1_000))
                .andExpect(jsonPath("$.clients.length()").value(1));

        mockMvc.perform(get("/api/usage/by-client").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTokens").value(9_999_999));
    }

    @Test
    void invertedWindowIsRejected() throws Exception {
        mockMvc.perform(get("/api/usage/by-client").contextPath("/api")
                        .param("from", "2026-09-01").param("to", "2026-04-01")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void windowLongerThanTheCapIsRejected() throws Exception {
        mockMvc.perform(get("/api/usage/by-client").contextPath("/api")
                        .param("from", "2000-01-01").param("to", "2026-09-01")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unparsableDateIsRejectedAsABadRequest() throws Exception {
        mockMvc.perform(get("/api/usage/by-client").contextPath("/api")
                        .param("from", "hier")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/usage/by-client").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ledgerHoldsNoContentAtAll() {
        // Garantie structurelle : l'entité n'a que des identifiants, des volumes et un horodatage.
        turn(aliceId, aliceProject, aliceHost, 10L, 10L);
        UsageTurn stored = usageTurnRepository.findByUserIdOrderByOccurredAtDesc(aliceId).get(0);

        assertThat(stored.getClass().getDeclaredFields())
                .noneMatch(field -> field.getType().equals(String.class));
    }
}
