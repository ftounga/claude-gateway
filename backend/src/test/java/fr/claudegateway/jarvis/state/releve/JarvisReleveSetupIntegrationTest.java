package fr.claudegateway.jarvis.state.releve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fr.claudegateway.atelier.AtelierMcpTurnLauncher;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.veille.JarvisWatchDtos;
import fr.claudegateway.atelier.veille.JarvisWatchExceptions;
import fr.claudegateway.atelier.veille.JarvisWatchItems;
import fr.claudegateway.atelier.veille.JarvisWatchProbeRunner;
import fr.claudegateway.atelier.veille.JarvisWatchService;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.runner.RunnerLiveness;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** F-247 / SF-247-08 — veille_poser(usage: etat), verbes de lecture, « Tout valider », tour d'inventaire unique. */
@SpringBootTest
@ActiveProfiles("test")
class JarvisReleveSetupIntegrationTest {

    @MockitoBean private JarvisWatchProbeRunner probes;
    @MockitoBean private RunnerLiveness liveness;
    @MockitoBean private AtelierMcpTurnLauncher launcher;

    @Autowired private JarvisWatchService watches;
    @Autowired private JarvisReleveSetupService setup;
    @Autowired private JarvisStateRegistry registry;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private UserRepository users;

    private UUID alice;
    private UUID bob;
    private RunnerHost host;
    private Workspace subject;

    @BeforeEach
    void setUp() {
        alice = user();
        bob = user();
        host = hosts.save(RunnerHost.builder().userId(alice).name("CAGIP").rootName("dev").os("linux")
                .shell("posix").elevated(false).runnerCapabilities("files,bash,low_priority").build());
        subject = workspaces.save(Workspace.builder().userId(alice).name("SSO").hostId(host.getId())
                .executionTarget(fr.claudegateway.atelier.WorkspaceExecutionTarget.RUNNER).createdAt(OffsetDateTime.now())
                .build());
        when(liveness.isAlive(any(), any())).thenReturn(true);
    }

    private UUID user() {
        return users.save(User.builder().email("setup-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build()).getId();
    }

    private JarvisWatchDtos.ProposeRequest state(String name, String command) {
        return new JarvisWatchDtos.ProposeRequest(name, "Jira", "Mes tickets", command, null, List.of(), null, "etat");
    }

    @Test
    @DisplayName("usage etat : relevé STATE, toutes les heures de 8 h à 20 h ; une écriture est refusée à l'enregistrement")
    void proposeState() {
        JarvisWatchDtos.JarvisWatchView v = watches.propose(alice, subject, state("Tickets", "jira issue list --plain"));
        assertThat(v.purpose()).isEqualTo("STATE");
        assertThat(v.runTimes()).hasSize(13).first().isEqualTo("08:00");
        assertThat(v.proposed()).isTrue();
        assertThatThrownBy(() -> watches.propose(alice, subject, state("Déploiements",
                "kubectl rollout restart deploy/api")))
                .isInstanceOf(JarvisWatchExceptions.UnsafeProbeException.class);
    }

    @Test
    @DisplayName("Tout valider : chaque sonde est testée puis activée si le test passe ; isolation")
    void validateAll() {
        watches.propose(alice, subject, state("Tickets", "jira issue list --plain"));
        watches.propose(alice, subject, state("MR", "glab mr list -F json"));
        when(probes.run(any(), any())).thenAnswer(inv -> {
            fr.claudegateway.atelier.veille.JarvisWatch w = inv.getArgument(0);
            return "MR".equals(w.getName())
                    ? new JarvisWatchProbeRunner.Outcome(List.of(), "probe_exit", "jeton expiré", 5L)
                    : new JarvisWatchProbeRunner.Outcome(List.of(new JarvisWatchItems.Item("PROJ-1", "t", "En cours",
                            "", "", "")), null, null, 5L);
        });

        assertThat(watches.validateAllState(bob, host.getId())).isEmpty();
        List<JarvisWatchDtos.ValidateResult> out = watches.validateAllState(alice, host.getId());

        assertThat(out).hasSize(2);
        assertThat(out).filteredOn(JarvisWatchDtos.ValidateResult::activated).extracting(
                JarvisWatchDtos.ValidateResult::name).containsExactly("Tickets");
        assertThat(out).filteredOn(r -> !r.activated()).extracting(JarvisWatchDtos.ValidateResult::error)
                .containsExactly("jeton expiré");
    }

    @Test
    @DisplayName("Mise en place : refusée poste éteint ou runner ancien ; sinon un tour unique dans le sujet, daté")
    void setupTurn() {
        when(liveness.isAlive(any(), any())).thenReturn(false);
        assertThat(setup.start(alice, host.getId()).code()).isEqualTo("host_offline");
        when(liveness.isAlive(any(), any())).thenReturn(true);
        host.setRunnerCapabilities("files,bash");
        hosts.save(host);
        assertThat(setup.start(alice, host.getId()).code()).isEqualTo("runner_update");
        verify(launcher, never()).launchWithBanner(any(), any(), anyString(), anyString());

        host.setRunnerCapabilities("files,bash,low_priority");
        hosts.save(host);
        UUID turn = UUID.randomUUID();
        when(launcher.launchWithBanner(eq(alice), eq(subject.getId()), anyString(), anyString()))
                .thenReturn(new AtelierMcpTurnLauncher.LaunchResult(turn, false, null, false));
        JarvisReleveSetupService.Setup s = setup.start(alice, host.getId());
        assertThat(s.started()).isTrue();
        assertThat(s.turnId()).isEqualTo(turn);
        verify(launcher).launchWithBanner(eq(alice), eq(subject.getId()), eq(JarvisReleveSetupService.PROMPT),
                eq(JarvisReleveSetupService.BANNER));
        assertThat(registry.settingsOf(alice, host.getId()).getReleveSetupAt()).isNotNull();
    }
}
