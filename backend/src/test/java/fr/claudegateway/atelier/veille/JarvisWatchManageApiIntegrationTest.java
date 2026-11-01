package fr.claudegateway.atelier.veille;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
 * F-230 / SF-230-04 — tenir ses veilles sous isolation : pause, reprise, heures, budget en euros, liste du compte
 * (filtrée par poste), mesure d'adoption réservée à l'administrateur.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisWatchManageApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private JarvisWatchRepository watchRepository;
    @Autowired private JarvisWatchService service;

    @MockitoBean private RunnerToolGateway gateway;
    @MockitoBean private RunnerLiveness liveness;

    private User alice;
    private String aliceToken;
    private String adminToken;
    private String bobToken;
    private Workspace subject;
    private RunnerHost host;
    private String watchId;

    @BeforeEach
    void setUp() throws Exception {
        watchRepository.deleteAll();
        alice = userRepository.save(User.builder().email("veille-tenir-alice-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);
        User bob = userRepository.save(User.builder().email("veille-tenir-bob-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        bobToken = jwtService.generateToken(bob);
        User admin = userRepository.save(User.builder().email("veille-tenir-admin-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        adminToken = jwtService.generateToken(admin);
        host = hostRepository.save(RunnerHost.builder().userId(alice.getId()).name("poste")
                .rootName("dev").os("linux").shell("posix").elevated(false).lastSeenAt(OffsetDateTime.now())
                .build());
        subject = workspaceRepository.save(Workspace.builder().userId(alice.getId()).name("cagip")
                .hostId(host.getId()).projectPath("cagip").executionTarget(WorkspaceExecutionTarget.RUNNER)
                .build());
        when(liveness.isAlive(any(), any())).thenReturn(true);
        when(gateway.bash(any(), anyString(), anyString(), isNull(), anyLong(), isNull()))
                .thenReturn(new RunnerCallResult(true, "", false, 0, 80L, null, null, null, "[{\"id\":\"A\"}]", false));
        watchId = service.propose(alice.getId(), subject, new ProposeRequest("Agenor", "Jira", "p",
                "curl -s https://x/search", null, List.of("09:00"), null)).id().toString();
    }

    private String base() {
        return "/api/workspaces/" + subject.getId() + "/watches";
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    @Test
    void aProposalCannotBePausedThenTheOwnerHoldsTheWatch() throws Exception {
        mockMvc.perform(as(put(base() + "/" + watchId), aliceToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("watch_conflict"));
        mockMvc.perform(as(post(base() + "/" + watchId + "/test"), aliceToken)).andExpect(jsonPath("$.ok").value(true));
        mockMvc.perform(as(post(base() + "/" + watchId + "/accept"), aliceToken)).andExpect(status().isOk());

        mockMvc.perform(as(put(base() + "/" + watchId), aliceToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.nextRunAt").doesNotExist());
        mockMvc.perform(as(put(base() + "/" + watchId), aliceToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true,\"runTimes\":[\"08:30\",\"16:00\"],\"monthlyBudgetEur\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.runTimes[1]").value("16:00"))
                .andExpect(jsonPath("$.monthlyBudgetEur").value(10.0))
                .andExpect(jsonPath("$.nextRunAt").isNotEmpty());
        mockMvc.perform(as(put(base() + "/" + watchId), aliceToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthlyBudgetEur\":500}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("watch_invalid"));
    }

    @Test
    void theAccountListIsScopedToTheOwnerAndToTheHost() throws Exception {
        mockMvc.perform(as(get("/api/jarvis/watches"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].workspaceName").value("cagip"))
                .andExpect(jsonPath("$[0].watch.name").value("Agenor"));
        mockMvc.perform(as(get("/api/jarvis/watches").param("hostId", host.getId().toString()), aliceToken))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(as(get("/api/jarvis/watches").param("hostId", UUID.randomUUID().toString()), aliceToken))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(as(get("/api/jarvis/watches"), bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(as(put(base() + "/" + watchId), bobToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theAdoptionMeasureIsForAdministratorsOnly() throws Exception {
        mockMvc.perform(as(get("/api/admin/jarvis/watches/measure"), aliceToken)).andExpect(status().isForbidden());
        mockMvc.perform(as(get("/api/admin/jarvis/watches/measure").param("days", "14"), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(14))
                .andExpect(jsonPath("$.watches").value(0))
                .andExpect(jsonPath("$.aiCostEur").value(0.0));
    }
}
