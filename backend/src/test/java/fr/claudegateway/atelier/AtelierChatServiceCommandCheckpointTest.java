package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpoint;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.audit.RunnerAuditOutcome;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * Le crochet <b>avant commande</b> branché dans la boucle (F-52 / SF-52-01).
 *
 * <p>La propriété défendue est la même que celle de SF-38-08, et elle s'observe de la même façon :
 * si le {@code RunnerToolGateway} n'a pas été appelé, <b>rien n'est parti</b> sur la machine. S'y
 * ajoute ce qui est propre à F-52 : le refus arrive <b>avant</b> la porte de confirmation, donc sans
 * faire cliquer l'utilisateur sur une commande que la gouvernance refuse de toute façon.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceCommandCheckpointTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    @Mock private RunnerAuditService auditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;

    private StubAiAgentProvider agentProvider;
    private RunnerConfirmationGate gate;
    private RecordingListener listener;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final RunnerTarget runnerTarget = new RunnerTarget(hostId, workspaceId, "projet");

    /** Contrôle de commande qui note ce qu'on lui a soumis et rend le verdict qu'on lui a donné. */
    private static final class RecordingCommandCheckpoint implements AtelierCheckpoint {
        final List<AtelierCheckpointContext> seen = new ArrayList<>();
        private final AtelierCheckpointVerdict verdict;

        RecordingCommandCheckpoint(AtelierCheckpointVerdict verdict) {
            this.verdict = verdict;
        }

        @Override
        public AtelierCheckpointKind kind() {
            return AtelierCheckpointKind.BEFORE_COMMAND;
        }

        @Override
        public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
            seen.add(context);
            return verdict;
        }
    }

    /**
     * Écoute qui note les demandes d'autorisation sans jamais y répondre.
     *
     * <p>Ne pas répondre est exactement ce qu'il faut ici : le test affirme qu'<b>aucune</b> demande
     * n'est posée. Si une l'était, l'absence de réponse la ferait expirer au bout de 200 ms et le
     * test échouerait sur la liste, pas sur une attente interminable.</p>
     */
    private static final class RecordingListener implements AtelierProgressListener {
        final List<AtelierConfirmRequest> requests = new ArrayList<>();

        @Override
        public void onAction(AtelierStepEvent step) {
        }

        @Override
        public void onText(String text) {
        }

        @Override
        public void onConfirmRequest(AtelierConfirmRequest request) {
            requests.add(request);
        }
    }

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        gate = new RunnerConfirmationGate(200L);
        listener = new RecordingListener();
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of());
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
    }

    private AtelierChatService serviceWith(AtelierCheckpoint... checkpoints) {
        return new AtelierChatService(workspaceService, messageRepository,
                (AiAgentProvider) agentProvider, byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, gate, auditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true),
                new AtelierCheckpointRunner(List.of(checkpoints)));
    }

    /**
     * Projet en cible RUNNER, <b>sans</b> porte de confirmation : le sujet des tests est le verrou de
     * gouvernance, pas le clic de l'utilisateur (SF-38-20 rend ce réglage légitime).
     */
    private void stubRunnerWorkspace() {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setHostId(hostId);
        workspace.setProjectPath("projet");
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        workspace.setAgentAskBeforeBash(false);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
    }

    private static RunnerCallResult bashOk() {
        return new RunnerCallResult(true, "", false, 0, 12L, null, null, null, "ok\n", false);
    }

    private AgentContentBlock.ToolResult lastToolResult() {
        AgentContentBlock.ToolResult found = null;
        for (AgentMessage message : agentProvider.lastRequest.messages()) {
            for (AgentContentBlock block : message.content()) {
                if (block instanceof AgentContentBlock.ToolResult result) {
                    found = result;
                }
            }
        }
        assertThat(found).as("aucun tool_result transmis au modèle").isNotNull();
        return found;
    }

    @Test
    void aBlockedCommandNeverReachesTheMachineAndCarriesTheCorrectiveAction() {
        stubRunnerWorkspace();
        AtelierChatService service = serviceWith(new RecordingCommandCheckpoint(
                AtelierCheckpointVerdict.block("Réécris le message sans la co-signature, puis relance.")));
        agentProvider.enqueueToolCall("bash", "command", "git commit -m \"x Co-Authored-By: Claude\"");
        agentProvider.enqueueFinal("Corrigé.");

        service.chat(userId, workspaceId, "commite");

        verify(runnerToolGateway, never()).bash(any(), anyString(), anyString(), any(), anyLong(), any());
        assertThat(lastToolResult().isError()).isTrue();
        assertThat(lastToolResult().content()).isEqualTo(
                "Commande contrôlée : Réécris le message sans la co-signature, puis relance.");
        // Refusé avant émission : le journal le dit, sans jamais porter le motif.
        verify(auditService).recordDenied(eq(userId), eq(runnerTarget), anyString(), eq("bash"),
                anyString(), eq(RunnerAuditOutcome.DENIED));
    }

    @Test
    void theCheckpointReceivesTheOwnerTheProjectTheCommandAndItsDirectory() {
        stubRunnerWorkspace();
        RecordingCommandCheckpoint checkpoint =
                new RecordingCommandCheckpoint(AtelierCheckpointVerdict.proceed());
        AtelierChatService service = serviceWith(checkpoint);
        when(runnerToolGateway.bash(eq(runnerTarget), anyString(), eq("npm test"), eq("frontend"),
                anyLong(), any())).thenReturn(bashOk());
        agentProvider.enqueueToolCall("bash", "command", "npm test", "cwd", "frontend");
        agentProvider.enqueueFinal("Fait.");

        service.chat(userId, workspaceId, "lance les tests");

        assertThat(checkpoint.seen).hasSize(1);
        AtelierCheckpointContext context = checkpoint.seen.get(0);
        assertThat(context.kind()).isEqualTo(AtelierCheckpointKind.BEFORE_COMMAND);
        // Isolation : le couple remis au contrôle est celui du tour, déjà vérifié possédé.
        assertThat(context.userId()).isEqualTo(userId);
        assertThat(context.workspaceId()).isEqualTo(workspaceId);
        assertThat(context.toolName()).isEqualTo("bash");
        assertThat(context.command()).isEqualTo("npm test");
        assertThat(context.cwd()).isEqualTo("frontend");
    }

    @Test
    void aPassingCheckpointLetsTheCommandThrough() {
        stubRunnerWorkspace();
        AtelierChatService service =
                serviceWith(new RecordingCommandCheckpoint(AtelierCheckpointVerdict.proceed()));
        when(runnerToolGateway.bash(eq(runnerTarget), anyString(), eq("npm test"), any(), anyLong(),
                any())).thenReturn(bashOk());
        agentProvider.enqueueToolCall("bash", "command", "npm test");
        agentProvider.enqueueFinal("Fait.");

        service.chat(userId, workspaceId, "lance les tests");

        verify(runnerToolGateway).bash(eq(runnerTarget), anyString(), eq("npm test"), any(), anyLong(),
                any());
        assertThat(lastToolResult().isError()).isFalse();
    }

    @Test
    void aFileWriteIsNeverSubmittedToTheCommandCheckpoint() {
        stubRunnerWorkspace();
        RecordingCommandCheckpoint checkpoint =
                new RecordingCommandCheckpoint(AtelierCheckpointVerdict.block("jamais"));
        AtelierChatService service = serviceWith(checkpoint);
        when(runnerToolGateway.writeFile(eq(runnerTarget), anyString(), eq("a.txt"), anyString()))
                .thenReturn(new RunnerCallResult(true, "", false, null, 5L, null, null, null, "", false));
        agentProvider.enqueueToolCall("write_file", "path", "a.txt", "content", "bonjour");
        agentProvider.enqueueFinal("Écrit.");

        service.chat(userId, workspaceId, "écris");

        assertThat(checkpoint.seen).isEmpty();
        assertThat(lastToolResult().isError()).isFalse();
    }

    @Test
    void withoutAnyCommandCheckpointTheLoopBehavesExactlyAsBefore() {
        stubRunnerWorkspace();
        AtelierChatService service = serviceWith();
        when(runnerToolGateway.bash(eq(runnerTarget), anyString(), eq("npm test"), any(), anyLong(),
                any())).thenReturn(bashOk());
        agentProvider.enqueueToolCall("bash", "command", "npm test");
        agentProvider.enqueueFinal("Fait.");

        AtelierChatService.AtelierChatResult result = service.chat(userId, workspaceId, "lance");

        verify(runnerToolGateway).bash(eq(runnerTarget), anyString(), eq("npm test"), any(), anyLong(),
                any());
        assertThat(result.reply()).isEqualTo("Fait.");
    }

    @Test
    void theRefusalArrivesBeforeTheConfirmationGate() {
        // Le projet demande une autorisation pour chaque commande (SF-38-08). La gouvernance refuse
        // AVANT : faire cliquer l'utilisateur sur une commande déjà condamnée lui ferait payer deux
        // fois le même refus.
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setHostId(hostId);
        workspace.setProjectPath("projet");
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        workspace.setAgentAskBeforeBash(true);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);

        AtelierChatService service = serviceWith(
                new RecordingCommandCheckpoint(AtelierCheckpointVerdict.block("Réécris le message.")));
        agentProvider.enqueueToolCall("bash", "command", "git commit -m \"x 🤖\"");
        agentProvider.enqueueFinal("Corrigé.");

        service.chatStreaming(userId, workspaceId, "commite", listener);

        assertThat(listener.requests).isEmpty();
        verify(runnerToolGateway, never()).bash(any(), anyString(), anyString(), any(), anyLong(), any());
        assertThat(lastToolResult().content()).startsWith("Commande contrôlée : ");
    }
}
