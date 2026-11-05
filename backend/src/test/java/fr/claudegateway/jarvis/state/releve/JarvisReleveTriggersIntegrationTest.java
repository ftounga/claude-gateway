package fr.claudegateway.jarvis.state.releve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.veille.JarvisWatch;
import fr.claudegateway.atelier.veille.JarvisWatchItems;
import fr.claudegateway.atelier.veille.JarvisWatchProbeRunner;
import fr.claudegateway.atelier.veille.JarvisWatchRepository;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.runner.RunnerLiveness;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** F-247 / SF-247-09 — relevé de départ (dû, plafond de 8 s, jeton) et relevé d'inactivité (20 min, une fois). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisReleveTriggersIntegrationTest {

    @MockitoBean private JarvisWatchProbeRunner probes;
    @MockitoBean private RunnerLiveness liveness;

    @Autowired private JarvisReleveTriggers triggers;
    @Autowired private JarvisStateRegistry registry;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private UserRepository users;
    @Autowired private JarvisWatchRepository watches;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;

    private UUID alice;
    private RunnerHost host;
    private Workspace subject;
    private final OffsetDateTime now = OffsetDateTime.now();

    @BeforeEach
    void setUp() {
        alice = users.save(User.builder().email("trig-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build()).getId();
        host = hosts.save(RunnerHost.builder().userId(alice).name("CAGIP").rootName("dev").os("linux")
                .shell("posix").elevated(false).runnerCapabilities("files,bash,low_priority").build());
        subject = workspaces.save(Workspace.builder().userId(alice).name("SSO").hostId(host.getId())
                .executionTarget(WorkspaceExecutionTarget.RUNNER).createdAt(OffsetDateTime.now()).build());
        when(liveness.isAlive(any(), any())).thenReturn(true);
        when(probes.run(any(), any(), anyLong())).thenReturn(new JarvisWatchProbeRunner.Outcome(List.of(
                new JarvisWatchItems.Item("PROJ-1", "SSO", "En revue", "", "", "")), null, null, 5L));
        when(probes.run(any(), any())).thenReturn(new JarvisWatchProbeRunner.Outcome(List.of(
                new JarvisWatchItems.Item("PROJ-1", "SSO", "En revue", "", "", "")), null, null, 5L));
    }

    private JarvisWatch probe(String name, OffsetDateTime lastRun) {
        return watches.save(JarvisWatch.builder().userId(alice).workspaceId(subject.getId()).name(name)
                .platform("Jira").scope("x").probeCommand("jira issue list --plain").runTimes("08:00")
                .timeZone("Europe/Paris").active(true).proposed(false).origin(JarvisWatch.ORIGIN_AGENT)
                .purpose(JarvisWatch.PURPOSE_STATE).lastRunAt(lastRun).createdAt(now).updatedAt(now).build());
    }

    private void activity(OffsetDateTime at) {
        jdbc.update("INSERT INTO runner_audit (id, user_id, workspace_id, call_id, tool, outcome, created_at) "
                + "VALUES (?,?,?,?,?,?,?)", UUID.randomUUID(), alice, subject.getId(), "c-" + UUID.randomUUID(), "bash",
                "ok", Timestamp.from(at.toInstant()));
    }

    @Test
    @DisplayName("Départ : relevé dû ⇒ les sondes passent et les faits entrent ; récent (< 15 min) ⇒ rien")
    void departureWhenDue() {
        probe("Tickets", null);
        assertThat(triggers.departure(alice, host.getId())).isEqualTo(1);
        assertThat(registry.byKey(alice, host.getId(), "JIRA:PROJ-1", 20)).isNotEmpty();
        assertThat(triggers.departure(alice, host.getId())).isZero();
    }

    @Test
    @DisplayName("Départ : 8 s au plus, quel que soit le nombre de sondes ; runner ancien ⇒ rien")
    void departureIsCapped() {
        for (int i = 0; i < 5; i++) {
            probe("Sonde " + i, null);
        }
        when(probes.run(any(), any(), anyLong())).thenAnswer(inv -> {
            Thread.sleep(3_000);
            return new JarvisWatchProbeRunner.Outcome(List.of(), null, null, 3_000L);
        });
        long t0 = System.nanoTime();
        int ran = triggers.departure(alice, host.getId());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).isLessThan(8_000);
        assertThat(ran).isBetween(1, 3);

        host.setRunnerCapabilities("files,bash");
        hosts.save(host);
        assertThat(triggers.departure(alice, host.getId())).isZero();
    }

    @Test
    @DisplayName("POST /runner/departure : sans jeton runner ⇒ 401")
    void departureNeedsRunnerToken() throws Exception {
        mvc.perform(post("/runner/departure")).andExpect(status().isUnauthorized());
        mvc.perform(post("/runner/departure").header("X-Runner-Token", "cgr_faux")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Inactivité : 20 min sans commande et du neuf depuis le dernier relevé ⇒ un relevé, une seule fois")
    void idleOnce() {
        JarvisWatch w = probe("Tickets", now.minusHours(2));
        activity(now.minusMinutes(5));
        assertThat(triggers.idle(alice, host.getId(), now)).isFalse();
        activity(now.minusMinutes(50));
        assertThat(triggers.idle(alice, host.getId(), now.plusMinutes(30))).isTrue();
        verify(probes, timeout(5_000)).run(any(), any());
        // Relevé fait après la dernière activité : plus rien à relever tant que le poste ne travaille pas.
        assertThat(triggers.idle(alice, host.getId(), now.plusMinutes(70))).isFalse();
        assertThat(watches.findById(w.getId()).orElseThrow().getLastRunAt()).isNotNull();
    }
}
