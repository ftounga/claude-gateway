package fr.claudegateway.atelier.veille;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-230 / SF-230-03 — le contrat de lecture des alertes (consommé par Jarvis, F-231 / SF-231-05) sous isolation :
 * la liste, une alerte, son ouverture ; un autre compte reçoit 404.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisAlertApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private JarvisWatchRepository watchRepository;
    @Autowired private JarvisWatchAlertRepository alertRepository;

    private String aliceToken;
    private String bobToken;
    private JarvisWatchAlert alert;
    private Workspace subject;

    @BeforeEach
    void setUp() {
        watchRepository.deleteAll();
        User alice = userRepository.save(User.builder().email("alerte-alice-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);
        User bob = userRepository.save(User.builder().email("alerte-bob-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        bobToken = jwtService.generateToken(bob);
        subject = workspaceRepository.save(Workspace.builder().userId(alice.getId()).name("cagip")
                .projectPath("cagip").executionTarget(WorkspaceExecutionTarget.RUNNER).build());
        OffsetDateTime now = OffsetDateTime.now();
        JarvisWatch watch = watchRepository.save(JarvisWatch.builder().userId(alice.getId())
                .workspaceId(subject.getId()).name("Agenor").platform("Jira").scope("p").probeCommand("curl -s x")
                .runTimes("09:00").timeZone("Europe/Paris").origin(JarvisWatch.ORIGIN_AGENT).active(true)
                .proposed(false).createdAt(now).updatedAt(now).build());
        alert = alertRepository.save(JarvisWatchAlert.builder().userId(alice.getId()).workspaceId(subject.getId())
                .watchId(watch.getId()).chantierNumber(2).sentence("Agenor : AG-2 est passé en bloqué.")
                .changesJson("[{\"kind\":\"STATUS\",\"id\":\"AG-2\",\"title\":\"Batch\",\"before\":\"En cours\","
                        + "\"after\":\"Bloqué\"}]")
                .changeCount(1).aiWritten(true).costUsd(new BigDecimal("0.05")).createdAt(now).build());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    @Test
    void theOwnerReadsAndOpensTheAlert() throws Exception {
        mockMvc.perform(as(get("/api/jarvis/alerts"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].watchName").value("Agenor"));
        mockMvc.perform(as(get("/api/jarvis/alerts/" + alert.getId()), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentence").value("Agenor : AG-2 est passé en bloqué."))
                .andExpect(jsonPath("$.changes[0].kind").value("STATUS"))
                .andExpect(jsonPath("$.changes[0].after").value("Bloqué"))
                .andExpect(jsonPath("$.platform").value("Jira"))
                .andExpect(jsonPath("$.chantierNumber").value(2))
                .andExpect(jsonPath("$.costEur").isNumber())
                .andExpect(jsonPath("$.openedAt").doesNotExist())
                // F-231 / SF-231-05 : l'écran Jarvis existe, l'alerte y mène (app.jarvis.route-enabled=true).
                .andExpect(jsonPath("$.link").value("/jarvis?alerte=" + alert.getId()));
        mockMvc.perform(as(post("/api/jarvis/alerts/" + alert.getId() + "/open"), aliceToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(as(post("/api/jarvis/alerts/" + alert.getId() + "/open"), aliceToken))
                .andExpect(status().isNoContent());
        assertThat(alertRepository.findById(alert.getId()).orElseThrow().getOpenedAt()).isNotNull();
    }

    @Test
    void anotherAccountSeesNothing() throws Exception {
        mockMvc.perform(as(get("/api/jarvis/alerts"), bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(as(get("/api/jarvis/alerts/" + alert.getId()), bobToken)).andExpect(status().isNotFound());
        mockMvc.perform(as(post("/api/jarvis/alerts/" + alert.getId() + "/open"), bobToken))
                .andExpect(status().isNotFound());
        assertThat(alertRepository.findById(alert.getId()).orElseThrow().getOpenedAt()).isNull();
    }
}
