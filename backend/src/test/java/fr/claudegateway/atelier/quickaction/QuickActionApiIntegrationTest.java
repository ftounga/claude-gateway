package fr.claudegateway.atelier.quickaction;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

/** F-275 / SF-275-01 — l'API des actions rapides : liste fusionnée, écriture, lancements, isolation. */
@TestPropertySource(properties = "app.atelier.storage-execution=true")
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuickActionApiIntegrationTest {

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
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private QuickActionRepository quickActionRepository;
    @Autowired private QuickActionLaunchRepository quickActionLaunchRepository;

    private User alice;
    private User bob;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        quickActionLaunchRepository.deleteAll();
        quickActionRepository.deleteAll();
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

    private UUID createWorkspace(User owner, String skillHeader) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry(".claude/skills/suivi-hebdo.md"));
            zip.write(skillHeader.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return workspaceService.create(owner.getId(), "projet", baos.toByteArray()).workspace().getId();
    }

    private static final String SUIVI = "---\ndescription: Point hebdo. Déclenché par « point hebdo » ou « suivi du jour ».\n"
            + "---\n# suivi-hebdo";

    private String url(UUID ws) {
        return "/api/workspaces/" + ws + "/quick-actions";
    }

    private JsonNode createAction(UUID ws, String body) throws Exception {
        String json = mockMvc.perform(post(url(ws)).contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(json);
    }

    @Test
    void aSkillThatDeclaresItsTriggersAppearsWithoutConfiguration() throws Exception {
        UUID ws = createWorkspace(alice, SUIVI);

        mockMvc.perform(get(url(ws)).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].label").value("Point hebdo"))
                .andExpect(jsonPath("$[0].source").value("SKILL"))
                .andExpect(jsonPath("$[0].skill").value("suivi-hebdo"))
                .andExpect(jsonPath("$[0].clickMode").value("DEPOSER"))
                .andExpect(jsonPath("$[1].phrase").value("suivi du jour"));
    }

    @Test
    void createListUpdateLaunchAndDelete() throws Exception {
        UUID ws = createWorkspace(alice, SUIVI);
        JsonNode created = createAction(ws, "{\"label\":\"Veille du comité\",\"phrase\":\"prépare la veille du comité\","
                + "\"clickMode\":\"ENVOYER\",\"suggestDays\":[1],\"suggestMoment\":\"MATIN\"}");
        String id = created.get("id").asText();

        mockMvc.perform(get(url(ws) + "?skills=false").contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].label").value("Veille du comité"))
                .andExpect(jsonPath("$[0].scope").value("SUJET"))
                .andExpect(jsonPath("$[0].suggestDays[0]").value(1));

        mockMvc.perform(put(url(ws) + "/" + id).contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Veille comité\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Veille comité"))
                .andExpect(jsonPath("$.clickMode").value("ENVOYER"));

        mockMvc.perform(post(url(ws) + "/launches").contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"c:" + id + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastRunAt", notNullValue()));
        mockMvc.perform(get(url(ws)).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$[0].lastRunAt", notNullValue()));

        mockMvc.perform(delete(url(ws) + "/" + id).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(url(ws) + "?skills=false").contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void aSkillLaunchIsRemembered() throws Exception {
        UUID ws = createWorkspace(alice, SUIVI);
        String key = "s:" + ws + ":suivi-hebdo:point hebdo";
        mockMvc.perform(post(url(ws) + "/launches").contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"" + key + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(url(ws)).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$[0].key").value(key))
                .andExpect(jsonPath("$[0].lastRunAt", notNullValue()));
    }

    @Test
    void invalidAndDuplicateRequestsAreRefused() throws Exception {
        UUID ws = createWorkspace(alice, SUIVI);
        mockMvc.perform(post(url(ws)).contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"\",\"phrase\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("QUICK_ACTION_INVALID"));
        mockMvc.perform(post(url(ws)).contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"a\",\"phrase\":\"x\",\"scope\":\"POSTE\"}"))
                .andExpect(status().isBadRequest());
        createAction(ws, "{\"label\":\"Point\",\"phrase\":\"x\"}");
        mockMvc.perform(post(url(ws)).contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"point\",\"phrase\":\"y\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("QUICK_ACTION_CONFLICT"));
    }

    @Test
    void anotherUserNeverSeesNorTouchesAlicesActions() throws Exception {
        UUID ws = createWorkspace(alice, SUIVI);
        JsonNode created = createAction(ws, "{\"label\":\"Partout\",\"phrase\":\"x\",\"scope\":\"COMPTE\"}");
        String id = created.get("id").asText();
        UUID bobWs = createWorkspace(bob, "# rien");

        mockMvc.perform(get(url(ws)).contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(url(ws)).contextPath("/api").header("Authorization", "Bearer " + bobToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"a\",\"phrase\":\"x\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(url(ws) + "/launches").contextPath("/api").header("Authorization", "Bearer " + bobToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"c:" + id + "\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put(url(bobWs) + "/" + id).contextPath("/api").header("Authorization", "Bearer " + bobToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"pris\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(url(bobWs) + "/" + id).contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(url(bobWs)).contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
