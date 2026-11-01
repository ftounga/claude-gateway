package fr.claudegateway.atelier.veille;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.veille.JarvisWatchDtos.JarvisWatchView;
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
 * F-230 / SF-230-01 — poser une veille sous isolation : l'agent propose (inactive), le PO teste puis active ; un
 * autre compte reçoit 404 partout ; le contrôle D5 refuse un secret en clair et un script modifié.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisWatchApiIntegrationTest {

    static final String OUTPUT = "[{\"id\":\"AG-1\",\"titre\":\"Alarme\",\"statut\":\"En cours\"},"
            + "{\"id\":\"AG-2\",\"titre\":\"Batch\",\"statut\":\"Bloqué\"}]";
    static final String SCRIPT = "#!/bin/sh\ncurl -s -H \"Authorization: Bearer $(cat ~/.jira)\" https://x/search\n";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private JarvisWatchRepository watchRepository;
    @Autowired private JarvisWatchService service;
    @Autowired private JarvisWatchToolExecutor executor;

    @MockitoBean private RunnerToolGateway gateway;
    @MockitoBean private RunnerLiveness liveness;

    private User alice;
    private String aliceToken;
    private String bobToken;
    private Workspace subject;

    @BeforeEach
    void setUp() {
        watchRepository.deleteAll();
        alice = userRepository.save(User.builder().email("veille-alice-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);
        User bob = userRepository.save(User.builder().email("veille-bob-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        bobToken = jwtService.generateToken(bob);
        RunnerHost host = hostRepository.save(RunnerHost.builder().userId(alice.getId()).name("poste")
                .rootName("dev").os("linux").shell("posix").elevated(false).lastSeenAt(OffsetDateTime.now())
                .build());
        subject = workspaceRepository.save(Workspace.builder().userId(alice.getId()).name("cagip")
                .hostId(host.getId()).projectPath("cagip").executionTarget(WorkspaceExecutionTarget.RUNNER)
                .build());
        when(liveness.isAlive(any(), any())).thenReturn(true);
        when(gateway.readFile(any(), anyString(), eq(".atelier/veilles/agenor.sh"))).thenReturn(ok(SCRIPT));
        when(gateway.bash(any(), anyString(), anyString(), isNull(), anyLong(), isNull())).thenReturn(bash(OUTPUT));
    }

    static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 3L, null, null, null, "", false);
    }

    static RunnerCallResult bash(String streamed) {
        return new RunnerCallResult(true, "", false, 0, 120L, null, null, null, streamed, false);
    }

    private String base() {
        return "/api/workspaces/" + subject.getId() + "/watches";
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    private JarvisWatchView propose() {
        return service.propose(alice.getId(), subject, new ProposeRequest("Agenor", "Jira",
                "Tableau agenor : en cours, bloqués ; jamais les done", "bash .atelier/veilles/agenor.sh",
                ".atelier/veilles/agenor.sh", List.of("14:00", "09:00"), 2));
    }

    @Test
    void theAgentProposesThePoTestsThenActivates() throws Exception {
        JarvisWatchView proposed = propose();
        assertThat(proposed.proposed()).isTrue();
        assertThat(proposed.active()).isFalse();
        assertThat(proposed.runTimes()).containsExactly("09:00", "14:00");
        assertThat(proposed.days()).isEqualTo("MONDAY_TO_FRIDAY");
        // La proposition ne lance rien : seul le script est relu (lecture seule).
        verify(gateway, never()).bash(any(), anyString(), anyString(), any(), anyLong(), any());

        String id = proposed.id().toString();
        mockMvc.perform(as(post(base() + "/" + id + "/accept"), aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("watch_untested"));

        mockMvc.perform(as(post(base() + "/" + id + "/test"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.summary").value("2 éléments : 1 en cours, 1 bloqué"))
                .andExpect(jsonPath("$.sample[0]").value("AG-1 Alarme"));

        mockMvc.perform(as(post(base() + "/" + id + "/accept"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.proposed").value(false))
                .andExpect(jsonPath("$.nextRunAt").isNotEmpty())
                .andExpect(jsonPath("$.testSummary").value("2 éléments : 1 en cours, 1 bloqué"));

        mockMvc.perform(as(get(base()), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].command").value("bash .atelier/veilles/agenor.sh"))
                .andExpect(jsonPath("$[0].chantierNumber").value(2));

        mockMvc.perform(as(delete(base() + "/" + id), aliceToken)).andExpect(status().isNoContent());
        assertThat(watchRepository.count()).isZero();
    }

    @Test
    void anotherAccountSeesNothing() throws Exception {
        String id = propose().id().toString();
        mockMvc.perform(as(get(base()), bobToken)).andExpect(status().isNotFound());
        mockMvc.perform(as(post(base() + "/" + id + "/test"), bobToken)).andExpect(status().isNotFound());
        mockMvc.perform(as(post(base() + "/" + id + "/accept"), bobToken)).andExpect(status().isNotFound());
        mockMvc.perform(as(delete(base() + "/" + id), bobToken)).andExpect(status().isNotFound());
        assertThat(watchRepository.count()).isEqualTo(1);
    }

    @Test
    void aFailingProbeIsReportedAndBlocksActivation() throws Exception {
        String id = propose().id().toString();
        when(gateway.bash(any(), anyString(), anyString(), isNull(), anyLong(), isNull()))
                .thenReturn(new RunnerCallResult(true, "", false, 1, 50L, null, null, null, "401", false));
        mockMvc.perform(as(post(base() + "/" + id + "/test"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.errorMessage").isNotEmpty());
        mockMvc.perform(as(post(base() + "/" + id + "/accept"), aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("watch_untested"));
    }

    @Test
    void aScriptChangedSinceTheProposalDoesNotRun() throws Exception {
        String id = propose().id().toString();
        when(gateway.readFile(any(), anyString(), eq(".atelier/veilles/agenor.sh")))
                .thenReturn(ok(SCRIPT + "curl -X DELETE https://x/issue/1\n"));
        mockMvc.perform(as(post(base() + "/" + id + "/test"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.containsString("a changé")));
    }

    @Test
    void anOfflineHostCannotBeTested() throws Exception {
        String id = propose().id().toString();
        when(liveness.isAlive(any(), any())).thenReturn(false);
        mockMvc.perform(as(post(base() + "/" + id + "/test"), aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("watch_host_offline"));
    }

    @Test
    void theToolRefusesSecretsAndUndeclaredScriptsAndCreatesNothing() {
        JarvisWatchToolExecutor.Outcome secret = executor.execute(alice.getId(), subject, "veille_poser",
                objectMapper.valueToTree(java.util.Map.of("nom", "X", "plateforme", "Jira", "perimetre", "p",
                        "commande", "curl -H 'Authorization: Bearer abcdefghijklmnopqrstuvwxyz012345' https://x",
                        "heures", List.of("09:00"))));
        assertThat(secret.error()).isTrue();
        assertThat(secret.content()).contains("jeton").doesNotContain("abcdefghijklmnop");

        JarvisWatchToolExecutor.Outcome undeclared = executor.execute(alice.getId(), subject, "veille_poser",
                objectMapper.valueToTree(java.util.Map.of("nom", "X", "plateforme", "Jira", "perimetre", "p",
                        "commande", "python3 probe.py", "heures", "09:00")));
        assertThat(undeclared.error()).isTrue();
        assertThat(undeclared.content()).contains("script");
        assertThat(watchRepository.count()).isZero();

        JarvisWatchToolExecutor.Outcome ok = executor.execute(alice.getId(), subject, "veille_poser",
                objectMapper.valueToTree(java.util.Map.of("nom", "Agenor", "plateforme", "Jira", "perimetre", "p",
                        "commande", "bash .atelier/veilles/agenor.sh", "script", ".atelier/veilles/agenor.sh",
                        "heures", "09:00, 14:00")));
        assertThat(ok.error()).isFalse();
        assertThat(ok.content()).contains("N'EST PAS active");
    }

    @Test
    void reproposingTheSameNameReplacesAndResetsTheTest() throws Exception {
        String id = propose().id().toString();
        mockMvc.perform(as(post(base() + "/" + id + "/test"), aliceToken)).andExpect(jsonPath("$.ok").value(true));
        mockMvc.perform(as(post(base() + "/" + id + "/accept"), aliceToken)).andExpect(status().isOk());
        JarvisWatchView again = propose();
        assertThat(again.id().toString()).isEqualTo(id);
        assertThat(again.proposed()).isTrue();
        assertThat(again.active()).isFalse();
        assertThat(again.testedAt()).isNull();
        assertThat(watchRepository.count()).isEqualTo(1);
    }

    @Test
    void aHostTerminalOrHostedSubjectRefuses() {
        Workspace hosted = workspaceRepository.save(Workspace.builder().userId(alice.getId()).name("hosted")
                .executionTarget(WorkspaceExecutionTarget.SANDBOX).build());
        assertThatThrownBy(() -> service.propose(alice.getId(), hosted, new ProposeRequest("A", "Jira", "p",
                "curl -s https://x", null, List.of("09:00"), null)))
                .isInstanceOf(JarvisWatchExceptions.WatchUnsupportedException.class);
        assertThat(executor.execute(alice.getId(), hosted, "veille_poser", objectMapper.createObjectNode()).error())
                .isTrue();
    }
}
