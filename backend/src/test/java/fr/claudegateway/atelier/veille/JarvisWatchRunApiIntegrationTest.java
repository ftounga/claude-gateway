package fr.claudegateway.atelier.veille;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.veille.JarvisWatchDtos.ProposeRequest;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.RunnerLiveness;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-230 / SF-230-02 — « Lancer maintenant » et l'historique sous isolation : base, rien de neuf, changement,
 * panne au 2ᵉ échec avec une ligne dans le fil ; un autre compte reçoit 404.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisWatchRunApiIntegrationTest {

    static final String ONE = "[{\"id\":\"AG-1\",\"titre\":\"Alarme\",\"statut\":\"En cours\"}]";
    static final String MOVED = "[{\"id\":\"AG-1\",\"titre\":\"Alarme\",\"statut\":\"Bloqué\"}]";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private JarvisWatchRepository watchRepository;
    @Autowired private JarvisWatchRunRepository runRepository;
    @Autowired private AtelierMessageRepository messageRepository;
    @Autowired private JarvisWatchAlertRepository alertRepository;
    @Autowired private JarvisWatchService service;

    @MockitoBean private RunnerToolGateway gateway;
    @MockitoBean private RunnerLiveness liveness;

    private User alice;
    private String aliceToken;
    private String bobToken;
    private Workspace subject;
    private String watchId;

    @BeforeEach
    void setUp() throws Exception {
        watchRepository.deleteAll();
        alice = userRepository.save(User.builder().email("veille-run-alice-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);
        User bob = userRepository.save(User.builder().email("veille-run-bob-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        bobToken = jwtService.generateToken(bob);
        RunnerHost host = hostRepository.save(RunnerHost.builder().userId(alice.getId()).name("poste")
                .rootName("dev").os("linux").shell("posix").elevated(false).lastSeenAt(OffsetDateTime.now())
                .build());
        subject = workspaceRepository.save(Workspace.builder().userId(alice.getId()).name("cagip")
                .hostId(host.getId()).projectPath("cagip").executionTarget(WorkspaceExecutionTarget.RUNNER)
                .build());
        when(liveness.isAlive(any(), any())).thenReturn(true);
        output(ONE);
        watchId = service.propose(alice.getId(), subject, new ProposeRequest("Agenor", "Jira", "p",
                "curl -s https://x/search", null, List.of("09:00"), 3)).id().toString();
        mockMvc.perform(as(post(base() + "/" + watchId + "/test"), aliceToken)).andExpect(jsonPath("$.ok").value(true));
        mockMvc.perform(as(post(base() + "/" + watchId + "/accept"), aliceToken)).andExpect(status().isOk());
    }

    private void output(String streamed) {
        when(gateway.bash(any(), anyString(), anyString(), isNull(), anyLong(), isNull()))
                .thenReturn(new RunnerCallResult(true, "", false, 0, 80L, null, null, null, streamed, false));
    }

    private void failing() {
        when(gateway.bash(any(), anyString(), anyString(), isNull(), anyLong(), isNull()))
                .thenReturn(new RunnerCallResult(true, "", false, 1, 80L, null, null, null, "401", false));
    }

    private String base() {
        return "/api/workspaces/" + subject.getId() + "/watches";
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    @Test
    void baselineThenNothingThenAChange() throws Exception {
        mockMvc.perform(as(post(base() + "/" + watchId + "/run"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BASELINE"))
                .andExpect(jsonPath("$.trigger").value("MANUAL"));
        mockMvc.perform(as(post(base() + "/" + watchId + "/run"), aliceToken))
                .andExpect(jsonPath("$.status").value("NO_CHANGE"));
        output(MOVED);
        mockMvc.perform(as(post(base() + "/" + watchId + "/run"), aliceToken))
                .andExpect(jsonPath("$.status").value("CHANGED"))
                .andExpect(jsonPath("$.changeCount").value(1));
        mockMvc.perform(as(get(base() + "/" + watchId + "/runs"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].status").value("CHANGED"));
        mockMvc.perform(as(get(base()), aliceToken))
                .andExpect(jsonPath("$[0].lastRunStatus").value("CHANGED"))
                .andExpect(jsonPath("$[0].broken").value(false))
                .andExpect(jsonPath("$[0].monthlyBudgetEur").value(5.0));
        // SF-230-03 : le changement — et lui seul — a produit une alerte et une ligne dans le fil du chantier.
        assertThat(alertRepository.findAll()).singleElement().satisfies(alert -> {
            assertThat(alert.getUserId()).isEqualTo(alice.getId());
            assertThat(alert.getChangeCount()).isEqualTo(1);
            assertThat(alert.getChantierNumber()).isEqualTo(3);
            assertThat(alert.getSentence()).isNotBlank();
        });
        assertThat(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(subject.getId(), alice.getId()))
                .anySatisfy(m -> assertThat(m.getContent()).startsWith("Veille Jarvis « Agenor » — "));
    }

    @Test
    void twoFailuresInARowBreakTheWatchAndLeaveALineInTheThread() throws Exception {
        failing();
        mockMvc.perform(as(post(base() + "/" + watchId + "/run"), aliceToken))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("probe_exit"));
        mockMvc.perform(as(post(base() + "/" + watchId + "/run"), aliceToken))
                .andExpect(jsonPath("$.status").value("FAILED"));
        mockMvc.perform(as(get(base()), aliceToken))
                .andExpect(jsonPath("$[0].broken").value(true))
                .andExpect(jsonPath("$[0].consecutiveFailures").value(2));
        assertThat(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(subject.getId(), alice.getId()))
                .anySatisfy(m -> {
                    assertThat(m.getContent()).contains("Veille Jarvis « Agenor »").contains("en panne");
                    assertThat(m.getChantierNumber()).isEqualTo(3);
                    assertThat(m.getRole()).isEqualTo("ASSISTANT");
                });
    }

    @Test
    void anotherAccountCannotRunNorRead() throws Exception {
        mockMvc.perform(as(post(base() + "/" + watchId + "/run"), bobToken)).andExpect(status().isNotFound());
        mockMvc.perform(as(get(base() + "/" + watchId + "/runs"), bobToken)).andExpect(status().isNotFound());
        assertThat(runRepository.count()).isZero();
    }

    @Test
    void aProposalCannotRunAndAnOfflineHostRefuses() throws Exception {
        String proposal = service.propose(alice.getId(), subject, new ProposeRequest("Autre", "Jira", "p",
                "curl -s https://x/search", null, List.of("09:00"), null)).id().toString();
        mockMvc.perform(as(post(base() + "/" + proposal + "/run"), aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("watch_conflict"));
        when(liveness.isAlive(any(), eq(subject.getHostId()))).thenReturn(false);
        mockMvc.perform(as(post(base() + "/" + watchId + "/run"), aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("watch_host_offline"));
    }
}
