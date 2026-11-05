package fr.claudegateway.jarvis.state.releve;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.veille.JarvisWatch;
import fr.claudegateway.atelier.veille.JarvisWatchItems;
import fr.claudegateway.atelier.veille.JarvisWatchRepository;
import fr.claudegateway.atelier.veille.JarvisWatchRun;
import fr.claudegateway.atelier.veille.JarvisWatchRunRepository;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.jarvis.state.JarvisStateSettings;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** F-247 / SF-247-07 — la garde du relevé (Z4, capacité, réglage, désarmement) et les instantanés devenus faits. */
@SpringBootTest
@ActiveProfiles("test")
class JarvisReleveIntegrationTest {

    @Autowired private JarvisReleveService releve;
    @Autowired private JarvisStateRegistry registry;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private UserRepository users;
    @Autowired private JarvisWatchRepository watches;
    @Autowired private JarvisWatchRunRepository runs;
    @Autowired private JdbcTemplate jdbc;

    private UUID alice;
    private UUID bob;
    private RunnerHost host;
    private Workspace subject;
    private JarvisWatch state;
    private final OffsetDateTime now = OffsetDateTime.now();

    @BeforeEach
    void setUp() {
        alice = user();
        bob = user();
        host = hosts.save(RunnerHost.builder().userId(alice).name("CAGIP").rootName("dev").os("linux")
                .shell("posix").elevated(false).runnerCapabilities("files,bash,bash_parallel_4,proof,low_priority")
                .build());
        subject = workspaces.save(Workspace.builder().userId(alice).name("SSO").hostId(host.getId())
                .createdAt(OffsetDateTime.now()).build());
        state = watch(JarvisWatch.PURPOSE_STATE, "Jira");
    }

    private UUID user() {
        return users.save(User.builder().email("releve-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build()).getId();
    }

    private JarvisWatch watch(String purpose, String platform) {
        return watches.save(JarvisWatch.builder().userId(alice).workspaceId(subject.getId()).name("Tickets")
                .platform(platform).scope("Mes tickets").probeCommand("jira issue list --plain").runTimes("08:00")
                .timeZone("Europe/Paris").active(true).proposed(false).origin(JarvisWatch.ORIGIN_AGENT)
                .purpose(purpose).createdAt(now).updatedAt(now).build());
    }

    private void audit(String tool, OffsetDateTime at) {
        jdbc.update("INSERT INTO runner_audit (id, user_id, workspace_id, call_id, tool, outcome, created_at) "
                + "VALUES (?,?,?,?,?,?,?)", UUID.randomUUID(), alice, subject.getId(), "c-" + UUID.randomUUID(), tool,
                "ok", Timestamp.from(at.toInstant()));
    }

    @Test
    @DisplayName("Poste calme, runner capable, client actif : le relevé peut partir ; un relevé récent ne gêne pas")
    void mayRunWhenQuiet() {
        audit("releve_sonde:bash", now.minusSeconds(30));
        audit("veille_sonde:bash", now.minusSeconds(30));
        assertThat(releve.mayRun(state, subject, now)).isTrue();
    }

    @Test
    @DisplayName("Z4 : une commande du PO au journal depuis moins de 2 min ⇒ rien ne part")
    void notWhileWorking() {
        audit("bash", now.minusSeconds(40));
        assertThat(releve.mayRun(state, subject, now)).isFalse();
        assertThat(releve.mayRun(state, subject, now.plusMinutes(3))).isTrue();
    }

    @Test
    @DisplayName("Runner ancien (sans low_priority) ou relevé coupé pour le client ⇒ aucun relevé")
    void capabilityAndSettings() {
        host.setRunnerCapabilities("files,bash,bash_parallel_4");
        hosts.save(host);
        assertThat(releve.mayRun(state, subject, now)).isFalse();
        host.setRunnerCapabilities("files,bash,low_priority");
        hosts.save(host);
        JarvisStateSettings s = registry.settingsOf(alice, host.getId());
        s.setReleveEnabled(false);
        registry.save(s);
        assertThat(releve.mayRun(state, subject, now)).isFalse();
    }

    @Test
    @DisplayName("D12 : plus de 3 préemptions dans la journée ⇒ relevé désarmé jusqu'au lendemain")
    void disarmedAfterPreemptions() {
        for (int i = 0; i < 4; i++) {
            runs.save(JarvisWatchRun.builder().userId(alice).workspaceId(subject.getId()).watchId(state.getId())
                    .trigger(JarvisWatchRun.SCHEDULED).status(JarvisWatchRun.YIELDED).errorCode("preempted")
                    .startedAt(now.minusMinutes(i + 1)).finishedAt(now.minusMinutes(i + 1)).durationMs(10L).build());
        }
        assertThat(releve.mayRun(state, subject, now)).isFalse();
        assertThat(releve.disarmed(alice, host.getId(), now.plusDays(1))).isFalse();
    }

    @Test
    @DisplayName("Instantané → faits : RELEVE pour un relevé, VEILLE pour une veille d'alerte, HAUTE ; isolation")
    void snapshotBecomesFacts() {
        List<JarvisWatchItems.Item> items = List.of(new JarvisWatchItems.Item("PROJ-1", "Migration SSO", "En revue",
                "Rémi", "2026-11-04", ""));
        releve.record(state, subject, items, now);
        JarvisStateRegistry.Fact status = registry.byKey(alice, host.getId(), "JIRA:PROJ-1", 20).stream()
                .filter(f -> f.attribute().equals("status")).findFirst().orElseThrow();
        assertThat(status.value()).isEqualTo("En revue");
        assertThat(status.source()).isEqualTo("RELEVE");
        assertThat(status.confidence()).isEqualTo("HAUTE");
        assertThat(status.objectKind()).isEqualTo("TICKET");

        JarvisWatch alert = watch(JarvisWatch.PURPOSE_ALERT, "GitLab");
        releve.record(alert, subject, List.of(new JarvisWatchItems.Item("grp/app!42", "MR", "opened", "", "", "")),
                now.plusSeconds(1));
        assertThat(registry.byKey(alice, host.getId(), "GITLAB:grp/app!42", 20).get(0).source()).isEqualTo("VEILLE");

        JarvisWatch foreign = JarvisWatch.builder().userId(bob).workspaceId(subject.getId()).platform("Jira")
                .purpose(JarvisWatch.PURPOSE_STATE).build();
        releve.record(foreign, subject, List.of(new JarvisWatchItems.Item("PROJ-9", "x", "Done", "", "", "")), now);
        assertThat(registry.recent(bob, host.getId(), 20)).isEmpty();
    }

    @Test
    @DisplayName("Familles et préfixes de clé alignés sur l'extraction")
    void keys() {
        assertThat(JarvisReleveService.prefix("Jira Cloud")).isEqualTo("JIRA");
        assertThat(JarvisReleveService.prefix("Monday")).isEqualTo("MONDAY");
        assertThat(JarvisReleveService.kind("Monday")).isEqualTo("TICKET");
        assertThat(JarvisReleveService.kind("GitHub")).isEqualTo("MR");
        assertThat(JarvisReleveService.kind("Grafana")).isEqualTo("OTHER");
    }
}
