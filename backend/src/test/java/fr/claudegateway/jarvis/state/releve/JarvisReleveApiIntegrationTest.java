package fr.claudegateway.jarvis.state.releve;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import fr.claudegateway.atelier.veille.JarvisWatch;
import fr.claudegateway.atelier.veille.JarvisWatchRepository;
import fr.claudegateway.atelier.veille.JarvisWatchService;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-273 / SF-273-01 — « Tout valider » poste hors ligne : un 409 lisible, une fois, rien de modifié ; la vue
 * « Relevé » rend la dernière erreur gardée ; un autre compte reçoit 404.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisReleveApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private JarvisWatchRepository watches;
    @Autowired private JwtService jwt;

    private String aliceToken;
    private String bobToken;
    private UUID host;
    private JarvisWatch probe;

    @BeforeEach
    void setUp() {
        User alice = user();
        aliceToken = jwt.generateToken(alice);
        bobToken = jwt.generateToken(user());
        // Aucun jeton de runner ⇒ le poste n'a jamais battu : hors ligne pour la vraie RunnerLiveness.
        host = hosts.save(RunnerHost.builder().userId(alice.getId()).name("EDENRED").rootName("dev").os("linux")
                .shell("posix").elevated(false).runnerCapabilities("files,bash,low_priority").build()).getId();
        Workspace subject = workspaces.save(Workspace.builder().userId(alice.getId()).name("secrets-azure-devops")
                .hostId(host).projectPath("secrets-azure-devops").executionTarget(WorkspaceExecutionTarget.RUNNER)
                .createdAt(OffsetDateTime.now()).build());
        OffsetDateTime now = OffsetDateTime.now();
        probe = watches.save(JarvisWatch.builder().userId(alice.getId()).workspaceId(subject.getId())
                .name("Relevé Jira").platform("Jira").scope("Mes tickets").probeCommand("python3 .atelier/releve/jira.py")
                .runTimes(JarvisWatchService.RELEVE_DEFAULT_TIMES).timeZone("Europe/Paris")
                .origin(JarvisWatch.ORIGIN_AGENT).purpose(JarvisWatch.PURPOSE_STATE).proposed(true).active(false)
                .lastErrorCode("probe_exit").lastErrorMessage("La sonde s'est terminée en erreur (code 1). "
                        + "Dernière ligne : « HTTP Error 401 »").lastErrorAt(now)
                .createdAt(now).updatedAt(now).build());
    }

    private User user() {
        return users.save(User.builder().email("releve-api-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, String token) {
        return r.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    @Test
    @DisplayName("Tout valider poste hors ligne : 409 lisible, sonde intacte ; un autre compte : 404")
    void validateAllOffline() throws Exception {
        mvc.perform(as(post("/api/jarvis/state/clients/" + host + "/releve/validate-all"), aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("watch_host_offline"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("hors ligne")));
        mvc.perform(as(post("/api/jarvis/state/clients/" + host + "/releve/validate-all"), bobToken))
                .andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(watches.findById(probe.getId()).orElseThrow().isProposed())
                .isTrue();
    }

    @Test
    @DisplayName("La vue Relevé rend la dernière erreur gardée de chaque sonde")
    void releveViewShowsTheKeptError() throws Exception {
        mvc.perform(as(get("/api/jarvis/state/clients/" + host + "/releve"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.probes[0].lastErrorCode").value("probe_exit"))
                .andExpect(jsonPath("$.probes[0].lastErrorMessage").value(
                        org.hamcrest.Matchers.containsString("HTTP Error 401")))
                .andExpect(jsonPath("$.probes[0].lastErrorAt").isNotEmpty());
        mvc.perform(as(get("/api/jarvis/state/clients/" + host + "/releve"), bobToken))
                .andExpect(status().isNotFound());
    }
}
