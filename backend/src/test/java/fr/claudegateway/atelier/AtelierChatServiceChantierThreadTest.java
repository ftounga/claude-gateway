package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.agent.AgentTurnMode;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner;
import fr.claudegateway.atelier.journey.JourneyMode;
import fr.claudegateway.atelier.journey.JourneyPhase;
import fr.claudegateway.atelier.journey.SubjectJourney;
import fr.claudegateway.atelier.journey.SubjectJourneyService;
import fr.claudegateway.billing.SpaceEntitlementService;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.pages.PageToolCatalog;
import fr.claudegateway.pages.PageToolExecutor;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.radar.RadarToolCatalog;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.runner.relay.RelayInterruptTarget.RelayInterruptOutcome;
import fr.claudegateway.teams.TeamsToolCatalog;

/**
 * <b>Le fil par chantier dans la boucle</b> (F-221 / SF-221-01) : ce qu'un tour de chantier relit et
 * écrit, sa vue du parcours, et l'interruption qui ne tue pas le fil d'à côté.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceChantierThreadTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-28T21:00:00Z");

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private RunnerCallDispatcher runnerCallDispatcher;
    @Mock private RunnerConfirmationGate confirmationGate;
    @Mock private RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private SpaceEntitlementService entitlements;
    @Mock private PageToolExecutor pageExecutor;
    @Mock private SubjectJourneyService journeyService;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final AtelierProgressListener listener = new AtelierProgressListener() {
        @Override
        public void onAction(AtelierStepEvent step) {
        }

        @Override
        public void onText(String text) {
        }
    };

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true),
                AtelierCheckpointRunner.none(), ProjectRulesSource.NONE, TeamsToolCatalog.none(), null,
                RadarToolCatalog.none(), null, new PageToolCatalog(entitlements), pageExecutor);
        service.setJourneyService(journeyService);

        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(thread());
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        when(journeyService.chantierOpenedAt(userId, workspaceId, 8)).thenReturn(Optional.of(T0.plusMinutes(4)));
        when(journeyService.chantierOpenedAt(userId, workspaceId, 7)).thenReturn(Optional.of(T0.minusMinutes(10)));

        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setHostId(UUID.randomUUID());
        workspace.setProjectPath("projet");
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        workspace.setAgentAskBeforeBash(false);
        // Une instance par chargement, comme en base : le fil d'un tour ne fuit jamais dans l'autre.
        when(workspaceService.requireOwned(userId, workspaceId)).thenAnswer(invocation -> {
            Workspace copy = new Workspace();
            copy.setId(workspace.getId());
            copy.setUserId(workspace.getUserId());
            copy.setHostId(workspace.getHostId());
            copy.setProjectPath(workspace.getProjectPath());
            copy.setSource(workspace.getSource());
            copy.setExecutionTarget(workspace.getExecutionTarget());
            copy.setAgentAskBeforeBash(false);
            return copy;
        });
    }

    private static AtelierMessage msg(String role, Integer chantier, int minute, String content) {
        return AtelierMessage.builder().id(UUID.randomUUID()).role(role).chantierNumber(chantier)
                .createdAt(T0.plusMinutes(minute)).content(content).build();
    }

    /** Le fil du sujet agenor du 28/10, réduit : la course du 7 et la planification du 8 entremêlées. */
    private static List<AtelierMessage> thread() {
        return List.of(
                msg("USER", null, 0, "cadrage-du-sujet"),
                msg("ASSISTANT", null, 1, "reponse-cadrage"),
                // le chantier 8 s'ouvre à la minute 4
                msg("USER", 7, 5, "course-du-chantier-7"),
                msg("USER", 8, 6, "planification-du-chantier-8"),
                msg("ASSISTANT", 7, 7, "reponse-course-7"),
                msg("ASSISTANT", 8, 8, "reponse-planification-8"));
    }

    @Test
    @DisplayName("un tour du fil 8 relit le passé commun et son fil — jamais la course du chantier 7 — et écrit dans le fil 8")
    void laneTurnReplaysItsThreadOnly() {
        agentProvider.enqueueFinal("Plan du chantier 8.");

        service.chatStreaming(userId, workspaceId, 8, "continue le chantier 8", AgentTurnMode.ACT, listener, false,
                List.of());

        String sent = agentProvider.messageSnapshots.get(0);
        assertThat(sent).contains("cadrage-du-sujet", "planification-du-chantier-8", "reponse-planification-8")
                .doesNotContain("course-du-chantier-7").doesNotContain("reponse-course-7");
        ArgumentCaptor<AtelierMessage> saved = ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).isNotEmpty()
                .allSatisfy(message -> assertThat(message.getChantierNumber()).isEqualTo(8));
    }

    @Test
    @DisplayName("le fil commun relit tout le sujet, regroupé par tour, et écrit sans chantier")
    void commonLaneSeesEverything() {
        agentProvider.enqueueFinal("Vu.");

        service.chatStreaming(userId, workspaceId, "où en est-on ?", listener);

        String sent = agentProvider.messageSnapshots.get(0);
        assertThat(sent).contains("course-du-chantier-7", "planification-du-chantier-8");
        assertThat(sent.indexOf("reponse-course-7")).as("la réponse du 7 suit sa demande, avant le tour du 8")
                .isLessThan(sent.indexOf("planification-du-chantier-8"));
        ArgumentCaptor<AtelierMessage> saved = ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(message -> assertThat(message.getChantierNumber()).isNull());
    }

    @Test
    @DisplayName("l'historique d'un fil applique la même règle que le rejeu ; sans fil, tout le sujet")
    void historyOfALane() {
        assertThat(service.history(userId, workspaceId, 8)).extracting(AtelierMessage::getContent)
                .containsExactly("cadrage-du-sujet", "reponse-cadrage", "planification-du-chantier-8",
                        "reponse-planification-8");
        assertThat(service.history(userId, workspaceId)).hasSize(6);
    }

    @Test
    @DisplayName("la note du tour du fil 8 vient de la vue du chantier 8 au focus, sans toucher au focus persisté")
    void laneTurnReadsTheLaneView() {
        SubjectJourney lane = SubjectJourney.builder().userId(userId).workspaceId(workspaceId)
                .mode(JourneyMode.GUIDE).phase(JourneyPhase.INVESTIGATION).chantierNumber(8)
                .chantierTitle("Migration DNS").planVersion(0).createdAt(T0).build();
        when(journeyService.forLane(userId, workspaceId, 8)).thenReturn(lane);
        agentProvider.enqueueFinal("Diagnostic en cours.");

        service.chatStreaming(userId, workspaceId, 8, "avance", AgentTurnMode.ACT, listener, false, List.of());

        verify(journeyService, atLeastOnce()).forLane(userId, workspaceId, 8);
        verify(journeyService, never()).focus(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(agentProvider.messageSnapshots.get(0)).contains("FIL DU CHANTIER 8", "Mode GUIDÉ");
    }

    @Test
    @DisplayName("interrompre le fil 8 pendant que le fil 7 tourne n'annule pas les commandes du poste")
    void interruptingALaneSparesTheOther() {
        AtomicReference<RelayInterruptOutcome> outcome = new AtomicReference<>();
        List<Boolean> otherActive = new ArrayList<>();
        when(runnerToolGateway.bash(any(), anyString(), anyString(), any(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    otherActive.add(service.otherLaneActive(userId, workspaceId, 8));
                    outcome.set(service.interruptLocally(userId, workspaceId, 8, "user_interrupt"));
                    return new RunnerCallResult(true, "", false, 0, 5L, null, null, null, "ok", false);
                });
        agentProvider.enqueueToolCall("bash", "command", "ls");
        agentProvider.enqueueFinal("Course du 7 terminée.");

        AtelierChatService.AtelierChatResult result = service.chatStreaming(userId, workspaceId, 7, "course",
                AgentTurnMode.ACT, listener, false, List.of());

        assertThat(otherActive).containsExactly(true);
        assertThat(outcome.get()).isEqualTo(new RelayInterruptOutcome(0, 0));
        verify(runnerCallDispatcher, never()).cancelWorkspace(eq(workspaceId), anyString());
        assertThat(result.interrupted()).as("le fil 7 n'est pas interrompu par le geste du fil 8").isFalse();
        assertThat(service.otherLaneActive(userId, workspaceId, 8)).as("plus rien ne tourne").isFalse();
    }

    @Test
    @DisplayName("les clefs d'état du tour sont propres à chaque fil ; le fil commun garde la clef d'avant")
    void turnKeysPerLane() {
        assertThat(AtelierChatService.turnKey(userId, workspaceId, null)).isEqualTo(userId + ":" + workspaceId);
        assertThat(AtelierChatService.turnKey(userId, workspaceId, 7))
                .isNotEqualTo(AtelierChatService.turnKey(userId, workspaceId, 8))
                .isNotEqualTo(AtelierChatService.turnKey(userId, workspaceId, null));
    }

    // ------------------------------------------------------------------ SF-221-06 : le worktree

    private static RunnerCallResult out(String content, int exit) {
        return new RunnerCallResult(true, content, false, exit, 5L, null, null, null, content, false);
    }

    @Test
    @DisplayName("SF-221-06 : le fil 8 qui démarre pendant le fil 7 crée SON worktree, l'enregistre et y exécute ses outils")
    void aParallelChantierIsIsolatedInItsWorktree() {
        String id = LaneWorktree.id(workspaceId, 8);
        java.util.List<String> commands = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.List<String> targets = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> laneEightSystem = new AtomicReference<>();
        when(journeyService.chantierWorktree(userId, workspaceId, 8)).thenReturn(Optional.empty());
        when(runnerToolGateway.bash(any(), anyString(), anyString(), any(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    fr.claudegateway.runner.channel.RunnerTarget target = invocation.getArgument(0);
                    String command = invocation.getArgument(2);
                    commands.add(command);
                    targets.add(target.projectPath());
                    if (command.startsWith("git rev-parse")) {
                        return out("true\n", 0);
                    }
                    if (command.startsWith("git worktree add")) {
                        return out("Preparing worktree", 0);
                    }
                    if ("ls".equals(command)) {
                        // Le fil 7 tourne : le fil 8 démarre pendant ce temps-là.
                        service.chatStreaming(userId, workspaceId, 8, "planifie", AgentTurnMode.ACT, listener,
                                false, List.of());
                        laneEightSystem.set(agentProvider.lastRequest.system());
                    }
                    return out("ok", 0);
                });
        agentProvider.enqueueToolCall("bash", "command", "ls");        // fil 7
        agentProvider.enqueueToolCall("bash", "command", "pwd");       // fil 8, dans son worktree
        agentProvider.enqueueFinal("Plan du chantier 8.");             // fil 8
        agentProvider.enqueueFinal("Course du 7.");                    // fil 7

        service.chatStreaming(userId, workspaceId, 7, "course", AgentTurnMode.ACT, listener, false, List.of());

        assertThat(commands).containsSubsequence("ls", "git rev-parse --is-inside-work-tree",
                "git worktree add -b atelier/chantier-" + id + " ../.atelier-chantiers/" + id + " HEAD", "pwd");
        assertThat(targets.get(commands.indexOf("pwd"))).as("les outils du fil 8 visent son worktree")
                .isEqualTo(".atelier-chantiers/" + id);
        assertThat(targets.get(commands.indexOf("ls"))).as("le fil 7 reste dans la copie principale")
                .isEqualTo("projet");
        verify(journeyService).recordChantierWorktree(userId, workspaceId, 8, ".atelier-chantiers/" + id,
                "atelier/chantier-" + id);
        assertThat(agentProvider.messageSnapshots).anySatisfy(sent -> assertThat(sent)
                .contains("[Isolé]", "atelier/chantier-" + id));
        assertThat(laneEightSystem.get()).as("préfixe stable : le système ne parle pas du worktree")
                .doesNotContain("[Isolé]").doesNotContain(".atelier-chantiers");
    }

    @Test
    @DisplayName("SF-221-06 : collant — un worktree posé est repris, même seul, sans aucune commande git")
    void anIsolatedChantierStaysInItsWorktree() {
        when(journeyService.chantierWorktree(userId, workspaceId, 8))
                .thenReturn(Optional.of(new String[] {".atelier-chantiers/x-ch8", "atelier/chantier-x-ch8"}));
        java.util.List<String> targets = new ArrayList<>();
        when(runnerToolGateway.bash(any(), anyString(), anyString(), any(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    targets.add(((fr.claudegateway.runner.channel.RunnerTarget) invocation.getArgument(0)).projectPath());
                    return out("ok", 0);
                });
        agentProvider.enqueueToolCall("bash", "command", "pwd");
        agentProvider.enqueueFinal("Fait.");

        service.chatStreaming(userId, workspaceId, 8, "suite", AgentTurnMode.ACT, listener, false, List.of());

        assertThat(targets).containsExactly(".atelier-chantiers/x-ch8");
        assertThat(agentProvider.messageSnapshots.get(0)).contains("[Isolé]");
    }

    @Test
    @DisplayName("SF-221-06 : seul et sans worktree — la copie principale, aucune commande git, aucune note")
    void aLoneChantierUsesTheMainCopy() {
        when(journeyService.chantierWorktree(userId, workspaceId, 8)).thenReturn(Optional.empty());
        agentProvider.enqueueFinal("Fait.");

        service.chatStreaming(userId, workspaceId, 8, "suite", AgentTurnMode.ACT, listener, false, List.of());

        verify(runnerToolGateway, never()).bash(any(), anyString(), anyString(), any(), anyLong(), any());
        assertThat(agentProvider.messageSnapshots.get(0)).doesNotContain("[Isolé]").doesNotContain("[Sans isolation]");
    }

    @Test
    @DisplayName("SF-221-06 : projet non git — pas de worktree, le tour est prévenu « sans isolation »")
    void withoutGitTheChantierIsWarned() {
        when(journeyService.chantierWorktree(userId, workspaceId, 8)).thenReturn(Optional.empty());
        when(runnerToolGateway.bash(any(), anyString(), anyString(), any(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    String command = invocation.getArgument(2);
                    if (command.startsWith("git rev-parse")) {
                        return out("fatal: not a git repository", 128);
                    }
                    if ("ls".equals(command)) {
                        service.chatStreaming(userId, workspaceId, 8, "planifie", AgentTurnMode.ACT, listener,
                                false, List.of());
                    }
                    return out("ok", 0);
                });
        agentProvider.enqueueToolCall("bash", "command", "ls");
        agentProvider.enqueueFinal("Plan du 8.");
        agentProvider.enqueueFinal("Course du 7.");

        service.chatStreaming(userId, workspaceId, 7, "course", AgentTurnMode.ACT, listener, false, List.of());

        verify(journeyService, never()).recordChantierWorktree(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any());
        assertThat(agentProvider.messageSnapshots).anySatisfy(sent -> assertThat(sent).contains("[Sans isolation]"));
    }
}
