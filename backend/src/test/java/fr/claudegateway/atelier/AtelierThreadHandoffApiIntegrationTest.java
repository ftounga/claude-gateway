package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.WorkspaceService.CreatedWorkspace;
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
 * La passation du fil (F-117 / SF-117-09) sous isolation : le propriétaire lit et corrige SA passation ;
 * un autre utilisateur, même abonné, reçoit un 404 indiscernable et ne peut rien écrire.
 */
@TestPropertySource(properties = "app.atelier.storage-execution=true")
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AtelierThreadHandoffApiIntegrationTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        StubAiAgentProvider stubAiAgentProvider() {
            return new StubAiAgentProvider();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private AtelierMessageRepository atelierMessageRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;

    private User alice;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        atelierMessageRepository.deleteAll();
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
        subscriptionRepository.save(Subscription.builder()
                .userId(user.getId())
                .planCode(PlanCode.GOLD)
                .status(SubscriptionStatus.ACTIVE)
                .build());
    }

    private UUID createWorkspace(User user, String summary) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry("notes.txt"));
            zip.write("x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        CreatedWorkspace created = workspaceService.create(user.getId(), "projet", baos.toByteArray());
        Workspace workspace = workspaceRepository.findById(created.workspace().getId()).orElseThrow();
        workspace.setChatThreadSummary(summary);
        workspaceRepository.save(workspace);
        return workspace.getId();
    }

    private String body(String summary) throws Exception {
        return objectMapper.writeValueAsString(Map.of("summary", summary));
    }

    private String url(UUID ws) {
        return "/api/workspaces/" + ws + "/chat/handoff";
    }

    @Test
    void leProprietaireLitSaPassation() throws Exception {
        UUID ws = createWorkspace(alice, AtelierThreadHandoffServiceTest.SUMMARY);

        mockMvc.perform(get(url(ws)).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary", Matchers.startsWith("## Objectif")))
                .andExpect(jsonPath("$.length", Matchers.greaterThan(0)));
    }

    @Test
    void sansPassationLeResumeEstNul() throws Exception {
        UUID ws = createWorkspace(alice, null);

        mockMvc.perform(get(url(ws)).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.length", Matchers.is(0)));
    }

    @Test
    void unAutreUtilisateurNeLitNiNeCorrigeLaPassationDAutrui() throws Exception {
        UUID ws = createWorkspace(alice, AtelierThreadHandoffServiceTest.SUMMARY);

        mockMvc.perform(get(url(ws)).contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(put(url(ws)).contextPath("/api").header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(AtelierThreadHandoffServiceTest.SUMMARY.replace("Merger.", "Pirate."))))
                .andExpect(status().isNotFound());

        assertThat(workspaceRepository.findById(ws).orElseThrow().getChatThreadSummary())
                .isEqualTo(AtelierThreadHandoffServiceTest.SUMMARY);
    }

    @Test
    void leProprietaireCorrigeEtRelitSaPassation() throws Exception {
        UUID ws = createWorkspace(alice, AtelierThreadHandoffServiceTest.SUMMARY);
        String corrected = AtelierThreadHandoffServiceTest.SUMMARY.replace("Merger.", "Merger puis déployer.");

        mockMvc.perform(put(url(ws)).contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(corrected)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary", Matchers.containsString("Merger puis déployer.")));

        mockMvc.perform(get(url(ws)).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.summary", Matchers.containsString("Merger puis déployer.")));
    }

    @Test
    void uneCorrectionHorsGabaritEstRefusee() throws Exception {
        UUID ws = createWorkspace(alice, AtelierThreadHandoffServiceTest.SUMMARY);

        mockMvc.perform(put(url(ws)).contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("Juste une phrase.")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", Matchers.is("invalid_handoff")));
    }

    @Test
    void onNeCreePasUnePassationQuiNExistePas() throws Exception {
        UUID ws = createWorkspace(alice, null);

        mockMvc.perform(put(url(ws)).contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(AtelierThreadHandoffServiceTest.SUMMARY)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", Matchers.is("no_handoff")));
    }
}
