package fr.claudegateway.atelier.skills;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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

/** F-177 / SF-177-03 — le catalogue de skills sous isolation : 200 propriétaire, 404 autrui. */
@TestPropertySource(properties = "app.atelier.storage-execution=true")
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkillCatalogApiIntegrationTest {

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

    private User alice;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
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

    private UUID createWorkspace() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry(".claude/skills/ticket-jira.md"));
            zip.write("---\ndescription: Rédige un ticket Jira.\n---\n# Ticket".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return workspaceService.create(alice.getId(), "projet", baos.toByteArray()).workspace().getId();
    }

    @Test
    void ownerListsHisSkills() throws Exception {
        UUID ws = createWorkspace();
        mockMvc.perform(get("/api/workspaces/" + ws + "/skills").contextPath("/api")
                .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("ticket-jira"))
                .andExpect(jsonPath("$[0].description").value("Rédige un ticket Jira."))
                .andExpect(jsonPath("$[0].origin").value("SUJET"));
    }

    @Test
    void anotherUserGets404() throws Exception {
        UUID ws = createWorkspace();
        mockMvc.perform(get("/api/workspaces/" + ws + "/skills").contextPath("/api")
                .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }
}
