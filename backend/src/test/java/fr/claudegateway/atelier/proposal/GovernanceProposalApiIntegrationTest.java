package fr.claudegateway.atelier.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-177 / SF-177-02 — les propositions de gouvernance sous isolation : le propriétaire relit, applique
 * et refuse ; un autre utilisateur reçoit un 404 indiscernable. Projet hébergé (stockage) : la portée
 * SUJET écrit dans le stockage du projet, et seulement au clic [Appliquer].
 */
@TestPropertySource(properties = "app.atelier.storage-execution=true")
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GovernanceProposalApiIntegrationTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        StubAiAgentProvider stubAiAgentProvider() {
            return new StubAiAgentProvider();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private GovernanceProposalRepository proposalRepository;
    @Autowired private GovernanceProposalService proposalService;

    private User alice;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        proposalRepository.deleteAll();
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        alice = userRepository.save(User.builder().email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        provisionGold(alice);
        aliceToken = jwtService.generateToken(alice);
        User bob = userRepository.save(User.builder().email("bob@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        provisionGold(bob);
        bobToken = jwtService.generateToken(bob);
    }

    private void provisionGold(User user) {
        subscriptionRepository.save(Subscription.builder().userId(user.getId()).planCode(PlanCode.GOLD)
                .status(SubscriptionStatus.ACTIVE).build());
    }

    private Workspace createWorkspace(User user) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry("notes.txt"));
            zip.write("x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return workspaceService.create(user.getId(), "projet", baos.toByteArray()).workspace();
    }

    private UUID propose(Workspace ws) {
        return proposalService.propose(alice.getId(), ws, "REGLE", "SUJET", "Jira",
                "Commentaires Jira courts.", "Demande du client").proposalId();
    }

    @Test
    void ownerAppliesAndTheFileIsWrittenOnlyThen() throws Exception {
        Workspace ws = createWorkspace(alice);
        UUID id = propose(ws);
        // Rien d'écrit à la proposition.
        assertThat(readOrNull(ws)).isNull();

        mockMvc.perform(get("/api/workspaces/" + ws.getId() + "/governance-proposals/" + id).contextPath("/api")
                .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.path").value("GOUVERNANCE.md"));

        mockMvc.perform(post("/api/workspaces/" + ws.getId() + "/governance-proposals/" + id + "/apply").contextPath("/api")
                .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
        assertThat(readOrNull(ws)).contains("## Jira").contains("Commentaires Jira courts.");

        mockMvc.perform(post("/api/workspaces/" + ws.getId() + "/governance-proposals/" + id + "/apply").contextPath("/api")
                .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict());
    }

    @Test
    void anotherUserGets404() throws Exception {
        Workspace ws = createWorkspace(alice);
        UUID id = propose(ws);

        mockMvc.perform(get("/api/workspaces/" + ws.getId() + "/governance-proposals/" + id).contextPath("/api")
                .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/workspaces/" + ws.getId() + "/governance-proposals/" + id + "/apply").contextPath("/api")
                .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        assertThat(readOrNull(ws)).isNull();
    }

    @Test
    void refuseWritesNothing() throws Exception {
        Workspace ws = createWorkspace(alice);
        UUID id = propose(ws);

        mockMvc.perform(post("/api/workspaces/" + ws.getId() + "/governance-proposals/" + id + "/refuse").contextPath("/api")
                .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUSED"));
        assertThat(readOrNull(ws)).isNull();
    }

    private String readOrNull(Workspace ws) {
        try {
            return workspaceService.readFile(alice.getId(), ws.getId(), "GOUVERNANCE.md");
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
