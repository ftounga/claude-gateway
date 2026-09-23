package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * Sous-boucle d'action {@code task} (F-150 / SF-150-02) : elle crée un worktree isolé côté runner,
 * y route la panoplie complète, impute son coût au tour, ne remonte que sa synthèse, et démonte
 * toujours le worktree. Distincte d'{@code explore} (elle écrit).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceTaskTest {

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
    /** Cible du PROJET (copie de travail réelle) : c'est là qu'on crée/retire le worktree. */
    private final RunnerTarget projectTarget = new RunnerTarget(hostId, workspaceId, "projet");

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
    void taskCreatesAWorktreeRoutesTheSubLoopThereThenTearsItDown() {
        stubRunnerWorkspace();
        when(runnerToolGateway.worktreeCreate(any(), anyString(), anyString())).thenReturn(ok(WORKTREE_JSON));
        when(runnerToolGateway.worktreeRemove(any(), anyString(), anyString())).thenReturn(ok(""));
        when(runnerToolGateway.writeFile(any(), anyString(), eq("a.txt"), eq("hop"))).thenReturn(ok("ok"));

        agentProvider.enqueueToolCall("task", "prompt", "écris a.txt");            // boucle principale
        agentProvider.enqueueToolCall("write_file", "path", "a.txt", "content", "hop"); // sous-boucle
        agentProvider.enqueueFinal("Sous-tâche accomplie : a.txt écrit.");         // synthèse
        agentProvider.enqueueFinal("Terminé.");                                    // boucle principale

        service.chat(userId, workspaceId, "délègue une tâche");

        // Worktree créé sur le PROJET, puis démonté (nettoyage garanti, D9).
        verify(runnerToolGateway).worktreeCreate(eq(projectTarget), anyString(), anyString());
        verify(runnerToolGateway).worktreeRemove(eq(projectTarget), anyString(), anyString());
        // L'écriture de la sous-boucle est routée vers le WORKTREE, jamais vers la copie de travail.
        ArgumentCaptor<RunnerTarget> target = ArgumentCaptor.forClass(RunnerTarget.class);
        verify(runnerToolGateway).writeFile(target.capture(), anyString(), eq("a.txt"), eq("hop"));
        assertThat(target.getValue().projectPath()).isEqualTo(".atelier-worktrees/wt1");
        assertThat(target.getValue().workspaceId()).isEqualTo(workspaceId);
        assertThat(target.getValue().hostId()).isEqualTo(hostId);
        // Seule la SYNTHÈSE remonte à la boucle principale (pas les fichiers du worktree).
        assertThat(lastToolResult().isError()).isFalse();
        assertThat(lastToolResult().content()).contains("Sous-tâche accomplie");
    }

    @Test
    void taskRefusesCleanlyWhenTheProjectIsNotGit() {
        stubRunnerWorkspace();
        when(runnerToolGateway.worktreeCreate(any(), anyString(), anyString()))
                .thenReturn(RunnerCallResult.backendError("not_git",
                        "`task` requiert un projet git : ce dossier n'est pas un dépôt git. "
                                + "Utilise `explore` pour lire, ou initialise git (git init)."));
        agentProvider.enqueueToolCall("task", "prompt", "écris a.txt");
        agentProvider.enqueueFinal("Compris.");

        service.chat(userId, workspaceId, "délègue une tâche");

        assertThat(lastToolResult().isError()).isTrue();
        assertThat(lastToolResult().content()).contains("git");
        // Rien n'a été créé : aucun worktree à démonter.
        verify(runnerToolGateway, never()).worktreeRemove(any(), anyString(), anyString());
    }

    @Test
    void taskGuidesWhenTheRunnerIsTooOld() {
        stubRunnerWorkspace();
        when(runnerToolGateway.worktreeCreate(any(), anyString(), anyString()))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.UNSUPPORTED_TOOL));
        agentProvider.enqueueToolCall("task", "prompt", "écris a.txt");
        agentProvider.enqueueFinal("Compris.");

        service.chat(userId, workspaceId, "délègue une tâche");

        assertThat(lastToolResult().isError()).isTrue();
        assertThat(lastToolResult().content()).contains("mets à jour le runner");
    }

    @Test
    void taskTearsDownTheWorktreeEvenWhenAnEmptyPromptIsGiven() {
        // prompt vide : refus AVANT création, donc rien à démonter — la garde tient sans fuite.
        stubRunnerWorkspace();
        agentProvider.enqueueToolCall("task", "prompt", "   ");
        agentProvider.enqueueFinal("Compris.");

        service.chat(userId, workspaceId, "délègue une tâche");

        assertThat(lastToolResult().isError()).isTrue();
        verify(runnerToolGateway, never()).worktreeCreate(any(), anyString(), anyString());
        verify(runnerToolGateway, never()).worktreeRemove(any(), anyString(), anyString());
    }

    @Test
    void theTaskToolIsDeclaredOnRunnerButNotOnSandbox() {
        Workspace runner = stubRunnerWorkspace();
        assertThat(service.buildTools(userId, runner).stream().map(AgentTool::name)).contains("task");

        Workspace sandbox = new Workspace();
        sandbox.setId(UUID.randomUUID());
        sandbox.setUserId(userId);
        sandbox.setSource(WorkspaceSource.ARCHIVE);
        sandbox.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        assertThat(service.buildTools(userId, sandbox).stream().map(AgentTool::name))
                .doesNotContain("task");
    }
}
