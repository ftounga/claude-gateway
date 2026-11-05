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
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fr.claudegateway.atelier.AtelierMcpTurnLauncher;
import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
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

/**
 * F-247 / SF-247-08 — veille_poser(usage: etat), verbes de lecture, « Tout valider », tour d'inventaire unique ;
 * SF-247-18 — sujet dédié, garde stricte de tout le poste, jamais une précision.
 */
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
    @Autowired private WorkspaceService workspaceService;
    @Autowired private AtelierMessageRepository messages;
    @Autowired private LiveTurnRegistry liveTurns;

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
    @DisplayName("Mise en place : refusée poste éteint ou runner ancien ; sinon un tour unique dans le sujet DÉDIÉ, daté")
    void setupTurn() {
        when(liveness.isAlive(any(), any())).thenReturn(false);
        assertThat(setup.start(alice, host.getId()).code()).isEqualTo("host_offline");
        when(liveness.isAlive(any(), any())).thenReturn(true);
        host.setRunnerCapabilities("files,bash");
        hosts.save(host);
        assertThat(setup.start(alice, host.getId()).code()).isEqualTo("runner_update");
        verify(launcher, never()).launchIfIdle(any(), any(), anyString(), anyString());

        host.setRunnerCapabilities("files,bash,low_priority");
        hosts.save(host);
        UUID turn = UUID.randomUUID();
        when(launcher.launchIfIdle(eq(alice), any(), anyString(), anyString())).thenReturn(Optional.of(turn));
        JarvisReleveSetupService.Setup s = setup.start(alice, host.getId());
        assertThat(s.started()).isTrue();
        assertThat(s.turnId()).isEqualTo(turn);
        assertThat(s.workspaceId()).isNotEqualTo(subject.getId());
        Workspace dedicated = workspaces.findById(s.workspaceId()).orElseThrow();
        assertThat(dedicated.getName()).isEqualTo(WorkspaceService.JARVIS_RELEVE_NAME);
        assertThat(dedicated.getUserId()).isEqualTo(alice);
        assertThat(dedicated.getHostId()).isEqualTo(host.getId());
        assertThat(dedicated.getJarvisReleveHostId()).isEqualTo(host.getId());
        assertThat(dedicated.isRunnerTarget()).isTrue();
        assertThat(dedicated.getProjectPath()).isEmpty();
        verify(launcher).launchIfIdle(eq(alice), eq(dedicated.getId()), eq(JarvisReleveSetupService.PROMPT),
                eq(JarvisReleveSetupService.BANNER));
        // Jamais le chemin qui dépose une précision dans un tour vivant, jamais le sujet de travail.
        verify(launcher, never()).launchWithBanner(any(), any(), anyString(), anyString());
        verify(launcher, never()).launchIfIdle(any(), eq(subject.getId()), anyString(), anyString());
        assertThat(messages.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(subject.getId(), alice)).isEmpty();
        assertThat(registry.settingsOf(alice, host.getId()).getReleveSetupAt()).isNotNull();
    }

    @Test
    @DisplayName("Sujet dédié créé une fois puis réutilisé ; rangé hors des projets du poste ; propre au compte")
    void dedicatedSubjectReused() {
        when(launcher.launchIfIdle(any(), any(), anyString(), anyString()))
                .thenReturn(Optional.of(UUID.randomUUID()));
        UUID first = setup.start(alice, host.getId()).workspaceId();
        UUID second = setup.start(alice, host.getId()).workspaceId();
        assertThat(second).isEqualTo(first);
        assertThat(workspaces.findByUserIdAndHostId(alice, host.getId()))
                .filteredOn(Workspace::isJarvisReleve).hasSize(1);
        assertThat(workspaceService.listByHost(alice, host.getId())).extracting(Workspace::getId)
                .containsExactly(subject.getId());
        assertThat(workspaceService.findJarvisReleve(bob, host.getId())).isEmpty();
    }

    @Test
    @DisplayName("Tour vivant dans un AUTRE sujet du même poste : 409 host_busy, rien n'est lancé ni écrit")
    void busyWhenTurnLiveInAnotherSubject() {
        LiveTurn live = liveTurns.open(alice, subject.getId());
        try {
            JarvisReleveSetupService.Setup s = setup.start(alice, host.getId());
            assertThat(s.started()).isFalse();
            assertThat(s.code()).isEqualTo("host_busy");
        } finally {
            liveTurns.close(live);
        }
        verify(launcher, never()).launchIfIdle(any(), any(), anyString(), anyString());
        verify(launcher, never()).launchWithBanner(any(), any(), anyString(), anyString());
        assertThat(workspaceService.findJarvisReleve(alice, host.getId())).isEmpty();
        assertThat(messages.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(subject.getId(), alice)).isEmpty();
    }

    @Test
    @DisplayName("Incident 2026-11-05 : tour du PO sur un AUTRE pod (demande sans réponse en base) : 409, aucune écriture")
    void busyWhenDemandUnansweredOnAnotherPod() {
        messages.save(AtelierMessage.builder().workspaceId(subject.getId()).userId(alice).role("USER")
                .content("fais le correctif, déploie-le").build());
        JarvisReleveSetupService.Setup s = setup.start(alice, host.getId());
        assertThat(s.code()).isEqualTo("host_busy");
        verify(launcher, never()).launchIfIdle(any(), any(), anyString(), anyString());
        assertThat(messages.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(subject.getId(), alice)).hasSize(1);

        // Un tour d'un AUTRE compte ne rend pas ce poste occupé (isolation).
        messages.deleteAll(messages.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(subject.getId(), alice));
        Workspace bobs = workspaces.save(Workspace.builder().userId(bob).name("Autre").executionTarget(
                fr.claudegateway.atelier.WorkspaceExecutionTarget.RUNNER).createdAt(OffsetDateTime.now()).build());
        messages.save(AtelierMessage.builder().workspaceId(bobs.getId()).userId(bob).role("USER").content("x").build());
        when(launcher.launchIfIdle(any(), any(), anyString(), anyString()))
                .thenReturn(Optional.of(UUID.randomUUID()));
        assertThat(setup.start(alice, host.getId()).started()).isTrue();
    }

    @Test
    @DisplayName("Tour déjà vivant dans le sujet dédié (course) : refus, jamais une précision")
    void refusedWhenDedicatedSubjectAlreadyLive() {
        when(launcher.launchIfIdle(any(), any(), anyString(), anyString())).thenReturn(Optional.empty());
        JarvisReleveSetupService.Setup s = setup.start(alice, host.getId());
        assertThat(s.code()).isEqualTo("host_busy");
        assertThat(registry.settingsOf(alice, host.getId()).getReleveSetupAt()).isNull();
        verify(launcher, never()).launchWithBanner(any(), any(), anyString(), anyString());
    }
}
