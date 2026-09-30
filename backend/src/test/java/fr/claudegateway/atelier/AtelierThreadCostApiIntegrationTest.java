package fr.claudegateway.atelier;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.hamcrest.Matchers;
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
import fr.claudegateway.atelier.WorkspaceService.CreatedWorkspace;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.quota.UsageTurn;
import fr.claudegateway.quota.UsageTurnRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * L'endpoint d'économie du fil (F-165 / SF-165-02) sous isolation : le propriétaire lit SON coût ;
 * un autre utilisateur, même abonné (la Forge ouvre tous les terminaux), se heurte au
 * {@code requireOwned} du service et reçoit un <b>404 indiscernable</b> — jamais le coût d'autrui.
 */
@TestPropertySource(properties = "app.atelier.storage-execution=true")
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AtelierThreadCostApiIntegrationTest {

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
    @Autowired private AtelierMessageRepository atelierMessageRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private UsageTurnRepository usageTurnRepository;

    private User alice;
    private String aliceToken;
    private User bob;
    private String bobToken;

    @BeforeEach
    void setUp() {
        usageTurnRepository.deleteAll();
        atelierMessageRepository.deleteAll();
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
        subscriptionRepository.save(Subscription.builder()
                .userId(user.getId())
                .planCode(PlanCode.GOLD)
                .status(SubscriptionStatus.ACTIVE)
                .build());
    }

    private UUID createWorkspace(User user) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry("notes.txt"));
            zip.write("x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        CreatedWorkspace created = workspaceService.create(user.getId(), "projet", baos.toByteArray());
        return created.workspace().getId();
    }

    private void recordTurn(UUID userId, UUID workspaceId, String costUsd) {
        usageTurnRepository.save(UsageTurn.builder()
                .userId(userId)
                .workspaceId(workspaceId)
                .inputTokens(100_000)
                .cacheReadTokens(90_000)
                .cacheWriteTokens(8_000)
                .outputTokens(2_000)
                .model("claude-opus-5")
                .providerCostUsd(new BigDecimal(costUsd))
                .occurredAt(OffsetDateTime.now())
                .build());
    }

    @Test
    void leProprietaireLitLEconomieDeSonFil() throws Exception {
        UUID ws = createWorkspace(alice);
        recordTurn(alice.getId(), ws, "0.50");

        mockMvc.perform(get("/api/workspaces/" + ws + "/chat/cost-summary").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency", Matchers.is("EUR")))
                .andExpect(jsonPath("$.turnCount", Matchers.is(1)))
                .andExpect(jsonPath("$.cumulativeEur", Matchers.is(0.46)))
                .andExpect(jsonPath("$.hotCachePercent", Matchers.is(90)))
                .andExpect(jsonPath("$.contextPages", Matchers.is(200)))
                .andExpect(jsonPath("$.breakdown.writePercent", Matchers.is(49)));
    }

    @Test
    void unAutreUtilisateurNeVoitJamaisLeCoutDunFilDAutrui() throws Exception {
        UUID ws = createWorkspace(alice);
        recordTurn(alice.getId(), ws, "0.50");

        // Bob est abonné (la Forge ouvre tous les terminaux), mais n'est PAS propriétaire du projet :
        // le requireOwned du service rend un 404 indiscernable, jamais un montant.
        mockMvc.perform(get("/api/workspaces/" + ws + "/chat/cost-summary").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }
}
