package fr.claudegateway.atelier.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
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

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** F-247 / SF-247-18 — l'activité d'un poste ou d'un sujet, quel que soit le pod (requêtes réelles, H2). */
@SpringBootTest
@ActiveProfiles("test")
class HostTurnActivityIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LiveTurnRegistry liveTurns;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private AtelierMessageRepository messages;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private UserRepository users;

    private final RemoteTurnSource remote = mock(RemoteTurnSource.class);
    private UUID alice;
    private UUID bob;
    private RunnerHost host;
    private Workspace work;
    private Workspace other;
    private HostTurnActivity activity;

    @BeforeEach
    void setUp() {
        alice = user();
        bob = user();
        host = hosts.save(RunnerHost.builder().userId(alice).name("CAGIP").rootName("dev").os("linux")
                .shell("posix").elevated(false).runnerCapabilities("files,bash,low_priority").build());
        work = subject(alice, "data-platform");
        other = subject(alice, "SSO");
        when(remote.remoteSummariesOf(any())).thenReturn(List.of());
        activity = new HostTurnActivity(liveTurns, remote, jdbc, Clock.systemDefaultZone());
    }

    private UUID user() {
        return users.save(User.builder().email("activity-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build()).getId();
    }

    private Workspace subject(UUID owner, String name) {
        return workspaces.save(Workspace.builder().userId(owner).name(name).hostId(host.getId())
                .executionTarget(WorkspaceExecutionTarget.RUNNER).createdAt(OffsetDateTime.now()).build());
    }

    private void message(Workspace w, String role, Integer chantier) {
        try {
            Thread.sleep(5); // des instants distincts : l'ordre se lit sur created_at
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        messages.save(AtelierMessage.builder().workspaceId(w.getId()).userId(w.getUserId()).role(role).content("x")
                .chantierNumber(chantier).build());
    }

    private boolean hostBusy() {
        return activity.hostBusy(alice, host.getId(), Duration.ofMinutes(2), true);
    }

    @Test
    @DisplayName("Poste calme : aucun tour, aucune demande en attente")
    void quietHost() {
        message(work, "USER", null);
        message(work, "ASSISTANT", null);
        assertThat(hostBusy()).isFalse();
        assertThat(activity.subjectBusy(alice, work.getId())).isFalse();
    }

    @Test
    @DisplayName("Tour vivant sur ce pod dans un autre sujet du poste (n'importe quel fil) : poste occupé")
    void liveTurnHere() {
        LiveTurn live = liveTurns.open(alice, other.getId(), 2);
        try {
            assertThat(hostBusy()).isTrue();
            assertThat(activity.subjectBusy(alice, other.getId())).isTrue();
            assertThat(activity.subjectBusy(alice, work.getId())).isFalse();
        } finally {
            liveTurns.close(live);
        }
    }

    @Test
    @DisplayName("Tour vivant chez un pod pair : occupé si l'on interroge les pairs")
    void liveTurnOnPeer() {
        when(remote.remoteSummariesOf(alice)).thenReturn(List.of(new TurnSummary(UUID.randomUUID(), alice,
                work.getId(), TurnSummary.RUNNING, null, "x", 0L, null, 0, 0, null, null, List.of(), null)));
        assertThat(hostBusy()).isTrue();
        assertThat(activity.subjectBusy(alice, work.getId())).isTrue();
        assertThat(activity.hostBusy(alice, host.getId(), Duration.ofMinutes(2), false)).isFalse();
    }

    @Test
    @DisplayName("Incident 2026-11-05 : demande sans réponse en base (tour sur un autre pod, relais muet) : occupé")
    void unansweredDemand() {
        message(work, "USER", null);
        assertThat(activity.hostBusy(alice, host.getId(), Duration.ofMinutes(2), false)).isTrue();
        assertThat(activity.subjectBusy(alice, work.getId())).isTrue();
        verify(remote, never()).remoteSummariesOf(bob);
        // Ni un autre compte, ni l'heure passée ne la voient.
        assertThat(activity.hostBusy(bob, host.getId(), Duration.ofMinutes(2), false)).isFalse();
        HostTurnActivity later = new HostTurnActivity(liveTurns, remote, jdbc,
                Clock.offset(Clock.systemDefaultZone(), HostTurnActivity.PENDING_WINDOW.plusMinutes(1)));
        assertThat(later.hostBusy(alice, host.getId(), Duration.ofMinutes(2), false)).isFalse();
        // Une réponse sur le MÊME fil la clôt ; une réponse sur un autre fil non.
        message(work, "ASSISTANT", 3);
        assertThat(activity.subjectBusy(alice, work.getId())).isTrue();
        message(work, "ASSISTANT", null);
        assertThat(activity.subjectBusy(alice, work.getId())).isFalse();
        assertThat(hostBusy()).isFalse();
    }

    @Test
    @DisplayName("Chantier autonome (ou routine) RUNNING sur le poste : occupé")
    void runningRun() {
        Timestamp now = Timestamp.from(OffsetDateTime.now().toInstant());
        jdbc.update("INSERT INTO chantier_autonomy_runs (id, user_id, workspace_id, chantier_number, status, started_at, "
                + "heartbeat_at) VALUES (?, ?, ?, 1, 'RUNNING', ?, ?)", UUID.randomUUID(), alice, other.getId(), now, now);
        assertThat(hostBusy()).isTrue();
        assertThat(activity.subjectBusy(alice, other.getId())).isTrue();
        assertThat(activity.subjectBusy(alice, work.getId())).isFalse();
    }

    @Test
    @DisplayName("Commande runner hors relevé depuis moins de 2 min : poste occupé ; une sonde de relevé non")
    void recentCommand() {
        audit("releve_sonde:Tickets");
        assertThat(hostBusy()).isFalse();
        audit("bash");
        assertThat(hostBusy()).isTrue();
        assertThat(activity.hostBusy(alice, host.getId(), null, true)).isFalse();
    }

    private void audit(String tool) {
        jdbc.update("INSERT INTO runner_audit (id, user_id, workspace_id, call_id, tool, outcome, created_at) "
                + "VALUES (?, ?, ?, ?, ?, 'ok', ?)", UUID.randomUUID(), alice, work.getId(), "c-" + UUID.randomUUID(),
                tool, Timestamp.from(OffsetDateTime.now().toInstant()));
    }
}
