package fr.claudegateway.atelier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.repoindex.RepoIndexEntry;
import fr.claudegateway.atelier.repoindex.RepoIndexPathRepository;
import fr.claudegateway.atelier.repoindex.RepoIndexProvider;
import fr.claudegateway.atelier.repoindex.RepoIndexStore;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;

/**
 * L'index de repo branché sur la boucle (F-148 / SF-148-07).
 *
 * <p>Ce qu'on vérifie : {@code glob} est servi depuis l'index — sans aller-retour runner — quand
 * c'est sûr (amorcé + projet non muté ce tour) ; il repart en direct dès qu'une mutation a eu lieu,
 * quand l'index est froid, et {@code grep} n'est jamais servi par l'index.</p>
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceRepoIndexTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private fr.claudegateway.runner.exec.RunnerToolGateway runnerToolGateway;
    @Mock private fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    @Mock private fr.claudegateway.runner.exec.RunnerConfirmationGate confirmationGate;
    @Mock private fr.claudegateway.runner.audit.RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;
    @Mock private RepoIndexPathRepository repoIndexRepo;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

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
        service.setRepoIndex(new RepoIndexProvider(
                new RepoIndexStore(runnerToolGateway, repoIndexRepo), Runnable::run));

        Workspace runner = new Workspace();
        runner.setId(workspaceId);
        runner.setUserId(userId);
        runner.setSource(WorkspaceSource.ARCHIVE);
        runner.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        runner.setHostId(hostId);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(runner);
        lenient().when(runnerHostService.declaredShell(hostId)).thenReturn(null);
        // buildSystemPrompt (cible RUNNER) lit CLAUDE.md, l'arborescence et les skills via le runner.
        lenient().when(runnerToolGateway.listFiles(any(RunnerTarget.class), any())).thenReturn(ok(""));
        lenient().when(runnerToolGateway.readFile(any(RunnerTarget.class), any(), any())).thenReturn(ok("x"));
        lenient().when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        lenient().when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        lenient().when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
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

    private void indexPrimedWith(String paths) {
        lenient().when(repoIndexRepo.existsByUserIdAndWorkspaceId(userId, workspaceId)).thenReturn(true);
        lenient().when(repoIndexRepo.findByUserIdAndWorkspaceId(userId, workspaceId))
                .thenReturn(Optional.of(RepoIndexEntry.builder().paths(paths).build()));
        lenient().when(repoIndexRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void globIsServedFromTheIndexWithoutRunnerCall() {
        indexPrimedWith("src/App.java\nREADME.md");
        agentProvider.enqueueToolCall("glob", "pattern", "**/*.java");
        agentProvider.enqueueFinal("fini");

        service.chat(userId, workspaceId, "trouve les fichiers Java");

        // Servi depuis l'index : aucun glob runner.
        verify(runnerToolGateway, never()).glob(any(), any(), any());
    }

    @Test
    void globGoesLiveAfterAMutationInTheTurn() {
        indexPrimedWith("src/App.java");
        lenient().when(runnerToolGateway.bash(any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(ok("ok"));
        lenient().when(runnerToolGateway.glob(any(), any(), any())).thenReturn(ok("src/App.java"));
        // Un bash (mutation possible) PUIS un glob : le glob doit repartir en direct.
        agentProvider.enqueueToolCall("bash", "command", "touch src/New.java");
        agentProvider.enqueueToolCall("glob", "pattern", "**/*.java");
        agentProvider.enqueueFinal("fini");

        service.chat(userId, workspaceId, "crée puis liste");

        verify(runnerToolGateway, times(1)).glob(any(), any(), any());
    }

    @Test
    void globGoesLiveWhenTheIndexIsCold() {
        when(repoIndexRepo.existsByUserIdAndWorkspaceId(userId, workspaceId)).thenReturn(false);
        lenient().when(runnerToolGateway.glob(any(), any(), any())).thenReturn(ok("src/App.java"));
        agentProvider.enqueueToolCall("glob", "pattern", "**/*.java");
        agentProvider.enqueueFinal("fini");

        service.chat(userId, workspaceId, "trouve");

        verify(runnerToolGateway, times(1)).glob(any(), any(), any());
    }

    @Test
    void grepIsNeverServedFromTheIndex() {
        indexPrimedWith("src/App.java");
        lenient().when(runnerToolGateway.grep(any(), any(), any())).thenReturn(ok("src/App.java:1: x"));
        agentProvider.enqueueToolCall("grep", "pattern", "TODO");
        agentProvider.enqueueFinal("fini");

        service.chat(userId, workspaceId, "cherche TODO");

        verify(runnerToolGateway, times(1)).grep(any(), any(), any());
    }
}
