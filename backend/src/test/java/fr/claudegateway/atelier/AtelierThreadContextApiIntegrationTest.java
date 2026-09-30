package fr.claudegateway.atelier;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
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
 * L'endpoint d'état mémoire du fil (F-165 / SF-165-03) sous isolation : le propriétaire lit l'état de SON
 * fil ; un autre utilisateur, même abonné (la Forge ouvre tous les terminaux), se heurte au
 * {@code requireOwned} du service et reçoit un <b>404 indiscernable</b> — jamais l'état d'autrui.
 */
@TestPropertySource(properties = "app.atelier.storage-execution=true")
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AtelierThreadContextApiIntegrationTest {

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

    private void recordTurn(UUID userId, UUID workspaceId, long input) {
        usageTurnRepository.save(UsageTurn.builder()
                .userId(userId)
                .workspaceId(workspaceId)
                .inputTokens(input)
                .cacheReadTokens(0)
                .cacheWriteTokens(0)
                .outputTokens(0)
                .model("claude-opus-5")
                .occurredAt(OffsetDateTime.now())
                .build());
    }

    @Test
    void leProprietaireLitLEtatDeSonFil() throws Exception {
        UUID ws = createWorkspace(alice);
        recordTurn(alice.getId(), ws, 60_000);

        mockMvc.perform(get("/api/workspaces/" + ws + "/chat/context-summary").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextTokens", Matchers.is(60000)))
                .andExpect(jsonPath("$.contextPages", Matchers.is(120)))
                .andExpect(jsonPath("$.triggerTokens", Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.compactionEnabled").exists())
                .andExpect(jsonPath("$.recallSemantic").exists());
    }

    @Test
    void unAutreUtilisateurNeVoitJamaisLEtatDunFilDAutrui() throws Exception {
        UUID ws = createWorkspace(alice);
        recordTurn(alice.getId(), ws, 60_000);

        // Bob est abonné mais n'est PAS propriétaire : le requireOwned du service rend un 404 indiscernable.
        mockMvc.perform(get("/api/workspaces/" + ws + "/chat/context-summary").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }
}
