package fr.claudegateway.atelier.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.account.AccountService;
import fr.claudegateway.atelier.AtelierChatService;
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
 * F-266 / SF-266-01 — les règles de tous mes postes, contexte Spring complet : créer, lister, retirer,
 * recopier une règle appliquée ; la consigne d'un projet hébergé les porte ; isolation A/B ; purge.
 */
@TestPropertySource(properties = "app.atelier.storage-execution=true")
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountGovernanceApiIntegrationTest {

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
    @Autowired private AccountGovernanceRuleRepository ruleRepository;
    @Autowired private AtelierChatService chatService;
    @Autowired private AccountService accountService;

    private User alice;
    private User bob;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        ruleRepository.deleteAll();
        proposalRepository.deleteAll();
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        alice = userRepository.save(User.builder().email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        provisionGold(alice);
        aliceToken = jwtService.generateToken(alice);
        bob = userRepository.save(User.builder().email("bob@example.com").emailVerified(true)
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

    private String createRule(String token, String title, String content) throws Exception {
        return mockMvc.perform(post("/api/governance/account-rules").contextPath("/api")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"content\":\"" + content + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(title))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void ownerCreatesListsReplacesAndDeletes() throws Exception {
        createRule(aliceToken, "Style des preuves", "Fond navy.");
        createRule(aliceToken, "style des PREUVES", "Fond navy #0B1020.");

        mockMvc.perform(get("/api/governance/account-rules").contextPath("/api")
                .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("Fond navy #0B1020."));

        UUID id = ruleRepository.findByUserIdOrderByTitleKeyAsc(alice.getId()).get(0).getId();
        mockMvc.perform(delete("/api/governance/account-rules/" + id).contextPath("/api")
                .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
        assertThat(ruleRepository.findByUserIdOrderByTitleKeyAsc(alice.getId())).isEmpty();
    }

    @Test
    void invalidRuleIsRefused() throws Exception {
        mockMvc.perform(post("/api/governance/account-rules").contextPath("/api")
                .header("Authorization", "Bearer " + aliceToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\" \",\"content\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anotherUserNeitherSeesNorDeletesNorPromotes() throws Exception {
        createRule(aliceToken, "Style des preuves", "Fond navy.");
        UUID id = ruleRepository.findByUserIdOrderByTitleKeyAsc(alice.getId()).get(0).getId();

        mockMvc.perform(get("/api/governance/account-rules").contextPath("/api")
                .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(delete("/api/governance/account-rules/" + id).contextPath("/api")
                .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        assertThat(ruleRepository.findById(id)).isPresent();

        Workspace ws = createWorkspace(alice);
        UUID proposal = proposalService.propose(alice.getId(), ws, "REGLE", "SUJET", "Jira",
                "Commentaires Jira courts.", null).proposalId();
        proposalService.apply(alice.getId(), ws.getId(), proposal);
        mockMvc.perform(post("/api/workspaces/" + ws.getId() + "/governance-proposals/" + proposal + "/promote")
                .contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());

        Workspace bobWs = createWorkspace(bob);
        assertThat(chatService.terminalSystemPrompt(bob.getId(), bobWs.getId())).doesNotContain("Fond navy.")
                .doesNotContain(AccountGovernanceService.HEADER);
    }

    @Test
    void appliedRuleIsPromotedAndReachesTheHostedPrompt() throws Exception {
        Workspace ws = createWorkspace(alice);
        UUID proposal = proposalService.propose(alice.getId(), ws, "REGLE", "SUJET", "Style des preuves",
                "Fond navy #0B1020.", null).proposalId();
        proposalService.apply(alice.getId(), ws.getId(), proposal);

        mockMvc.perform(post("/api/workspaces/" + ws.getId() + "/governance-proposals/" + proposal + "/promote")
                .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Style des preuves"))
                .andExpect(jsonPath("$.content").value("Fond navy #0B1020."));

        // Le sujet garde sa règle (même titre) : pas de doublon.
        String same = chatService.terminalSystemPrompt(alice.getId(), ws.getId());
        assertThat(same).doesNotContain(AccountGovernanceService.HEADER);
        // Un autre projet hébergé, sans règle propre, reçoit la règle du compte.
        Workspace other = createWorkspace(alice);
        String prompt = chatService.terminalSystemPrompt(alice.getId(), other.getId());
        assertThat(prompt).contains(AccountGovernanceService.HEADER + "## Style des preuves\n\nFond navy #0B1020.");
        assertThat(chatService.terminalSystemPrompt(alice.getId(), other.getId())).isEqualTo(prompt);
    }

    @Test
    void accountScopeProposalAppliesWithoutAHost() throws Exception {
        Workspace ws = createWorkspace(alice);
        UUID proposal = proposalService.propose(alice.getId(), ws, "REGLE", "COMPTE", "Tickets",
                "Toujours citer le ticket.", null).proposalId();
        assertThat(ruleRepository.findByUserIdOrderByTitleKeyAsc(alice.getId())).isEmpty();

        mockMvc.perform(post("/api/workspaces/" + ws.getId() + "/governance-proposals/" + proposal + "/apply")
                .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.scope").value("COMPTE"));
        assertThat(ruleRepository.findByUserIdOrderByTitleKeyAsc(alice.getId())).hasSize(1);
    }

    @Test
    void rulesArePurgedWithTheAccount() throws Exception {
        createRule(aliceToken, "Style des preuves", "Fond navy.");
        createRule(bobToken, "Style des preuves", "Fond navy.");

        accountService.deleteAccount(alice.getId());

        assertThat(ruleRepository.findByUserIdOrderByTitleKeyAsc(alice.getId())).isEmpty();
        assertThat(ruleRepository.findByUserIdOrderByTitleKeyAsc(bob.getId())).hasSize(1);
    }
}
