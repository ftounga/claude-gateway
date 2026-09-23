package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.resolution.ResolutionMemoryEntry;
import fr.claudegateway.atelier.resolution.ResolutionMemoryProvider;
import fr.claudegateway.atelier.resolution.ResolutionMemoryRepository;
import fr.claudegateway.atelier.resolution.ResolutionMemoryStore;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;

/**
 * La mémoire de résolutions branchée sur la boucle (F-148 / SF-148-08).
 *
 * <p>Ce qu'on vérifie : un tour abouti sur un poste est mémorisé ; une résolution proche est
 * réinjectée dans le MESSAGE (pas dans la consigne système → cache F-134 préservé) ; un projet sans
 * poste (SANDBOX) n'est jamais mémorisé.</p>
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceResolutionMemoryTest {

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
    @Mock private ResolutionMemoryRepository memoryRepo;

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
        service.setResolutionMemory(new ResolutionMemoryProvider(
                new ResolutionMemoryStore(memoryRepo), Runnable::run));

        lenient().when(runnerHostService.declaredShell(hostId)).thenReturn(null);
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

    private Workspace runnerWorkspace() {
        Workspace runner = new Workspace();
        runner.setId(workspaceId);
        runner.setUserId(userId);
        runner.setSource(WorkspaceSource.ARCHIVE);
        runner.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        runner.setHostId(hostId);
        return runner;
    }

    @Test
    void anAboutiTurnIsRemembered() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(runnerWorkspace());
        when(memoryRepo.findTop100ByUserIdAndHostIdOrderByCreatedAtDesc(userId, hostId))
                .thenReturn(List.of());
        agentProvider.enqueueFinal("Le proxy vit dans /etc/zscaler.conf");

        service.chat(userId, workspaceId, "où est configuré le proxy zscaler");

        ArgumentCaptor<ResolutionMemoryEntry> saved = ArgumentCaptor.forClass(ResolutionMemoryEntry.class);
        verify(memoryRepo).save(saved.capture());
        assertThat(saved.getValue().getHostId()).isEqualTo(hostId);
        assertThat(saved.getValue().getQuestion()).isEqualTo("où est configuré le proxy zscaler");
        assertThat(saved.getValue().getConclusion()).contains("/etc/zscaler.conf");
    }

    @Test
    void aCloseResolutionIsRecalledIntoTheMessageNotTheSystemPrompt() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(runnerWorkspace());
        when(memoryRepo.findTop100ByUserIdAndHostIdOrderByCreatedAtDesc(userId, hostId))
                .thenReturn(List.of(ResolutionMemoryEntry.builder()
                        .userId(userId).hostId(hostId).workspaceId(workspaceId)
                        .question("comment configurer le proxy zscaler du poste")
                        .conclusion("REPONSE_MEMOIRE : le proxy se règle dans /etc/zscaler.conf")
                        .files("etc/zscaler.conf")
                        .build()));
        agentProvider.enqueueFinal("ok");

        service.chat(userId, workspaceId, "configurer le proxy zscaler");

        String firstMessages = agentProvider.messageSnapshots.get(0);
        assertThat(firstMessages).contains("Déjà résolu sur ce poste");
        assertThat(firstMessages).contains("REPONSE_MEMOIRE");
        // Le rappel vit dans le MESSAGE, jamais dans la consigne système (cache F-134 préservé).
        assertThat(agentProvider.lastRequest.system()).doesNotContain("Déjà résolu");
        assertThat(agentProvider.lastRequest.system()).doesNotContain("REPONSE_MEMOIRE");
    }

    @Test
    void aSandboxProjectWithoutHostIsNeverRemembered() {
        Workspace sandbox = new Workspace();
        sandbox.setId(workspaceId);
        sandbox.setUserId(userId);
        sandbox.setSource(WorkspaceSource.ARCHIVE); // SANDBOX, aucun host_id
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(sandbox);
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md")).thenReturn("conv");
        agentProvider.enqueueFinal("une réponse");

        service.chat(userId, workspaceId, "une question quelconque");

        verify(memoryRepo, never()).save(any());
    }
}
