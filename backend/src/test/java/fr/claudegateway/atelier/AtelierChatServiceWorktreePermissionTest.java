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

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * Confirmation / permissions dans le worktree de {@code task} (F-150 / SF-150-03, décision PO 2) :
 * l'ÉDITION est automatique (acceptEdits, le worktree est sûr par construction), {@code bash} reste
 * TOUJOURS sous la porte + la politique SF-121-02. La permission est résolue par workspace (D10).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceWorktreePermissionTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    @Mock private fr.claudegateway.runner.exec.RunnerConfirmationGate confirmationGate;
    @Mock private fr.claudegateway.runner.audit.RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private static final String WORKTREE_JSON =
            "{\"worktreePath\":\".atelier-worktrees/wt1\",\"branch\":\"atelier/task/wt1\"}";

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true));

        when(confirmationGate.await(any(), any(), anyString(), any())).thenAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            return new fr.claudegateway.runner.exec.RunnerConfirmationGate.Outcome(
                    fr.claudegateway.runner.exec.RunnerConfirmationGate.Decision.ALLOW, null);
        });
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
        when(runnerToolGateway.worktreeCreate(any(), anyString(), anyString())).thenReturn(ok(WORKTREE_JSON));
        when(runnerToolGateway.worktreeRemove(any(), anyString(), anyString())).thenReturn(ok(""));
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false);
    }

    private Workspace stubRunnerWorkspace() {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setHostId(hostId);
        workspace.setProjectPath("projet");
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        return workspace;
    }

    @Test
    void anEditInTheWorktreeIsAutoEvenWhenAskBeforeEditIsSet() {
        stubRunnerWorkspace();
        service.setAskBeforeEdit(true); // le défaut demanderait une confirmation à chaque écriture…
        when(runnerToolGateway.writeFile(any(), anyString(), eq("a.txt"), eq("hop"))).thenReturn(ok("ok"));

        agentProvider.enqueueToolCall("task", "prompt", "écris a.txt");
        agentProvider.enqueueToolCall("write_file", "path", "a.txt", "content", "hop"); // sous-boucle
        agentProvider.enqueueFinal("Écrit.");                                            // synthèse
        agentProvider.enqueueFinal("Terminé.");                                          // principale

        service.chat(userId, workspaceId, "délègue");

        // …mais dans le worktree isolé, l'édition est AUTO : aucune confirmation n'est demandée.
        verify(confirmationGate, never()).await(any(), any(), anyString(), any());
        // et l'écriture a bien eu lieu, routée sur le worktree.
        verify(runnerToolGateway).writeFile(any(), anyString(), eq("a.txt"), eq("hop"));
    }

    @Test
    void bashInTheWorktreeStaysUnderTheGate() {
        Workspace workspace = stubRunnerWorkspace();
        workspace.setAgentAskBeforeBash(true); // bash sous la porte…
        when(runnerToolGateway.bash(any(), anyString(), eq("ls"), any(), anyLong(), any()))
                .thenReturn(new RunnerCallResult(true, "", false, 0, 5L, null, null, null, "ok", false));

        agentProvider.enqueueToolCall("task", "prompt", "liste les fichiers");
        agentProvider.enqueueToolCall("bash", "command", "ls"); // sous-boucle
        agentProvider.enqueueFinal("Listé.");                    // synthèse
        agentProvider.enqueueFinal("Terminé.");                  // principale

        service.chat(userId, workspaceId, "délègue");

        // …même dans le worktree : la porte est franchie (confirmation demandée) avant émission.
        verify(confirmationGate, atLeastOnce()).await(any(), any(), anyString(), any());
        verify(runnerToolGateway).bash(any(), anyString(), eq("ls"), any(), anyLong(), any());
    }
}
