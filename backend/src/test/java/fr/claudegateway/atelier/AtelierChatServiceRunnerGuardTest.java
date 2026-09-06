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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.ai.ModelCatalog;
import fr.claudegateway.atelier.AtelierProgressListener.AtelierConfirmRequest;
import fr.claudegateway.atelier.AtelierProgressListener.AtelierConfirmResolved;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.audit.RunnerAuditOutcome;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * Garde-fous d'exécution et traçabilité de la boucle Assistant en cible <b>RUNNER</b>
 * (F-38 / SF-38-08, décisions D7 et D11).
 *
 * <p>La propriété défendue ici est celle qui compte : une commande n'atteint <b>jamais</b> la
 * machine avant décision explicite. Les tests observent donc le {@code RunnerToolGateway} — s'il
 * n'a pas été appelé, rien n'est parti.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceRunnerGuardTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private ModelCatalog modelCatalog;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    @Mock private RunnerAuditService auditService;

    private StubAiAgentProvider agentProvider;
    private RunnerConfirmationGate gate;
    private AtelierChatService service;
    private RecordingListener listener;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        gate = new RunnerConfirmationGate(200L); // Silence = refus rapide, pour les tests d'échéance.
        listener = new RecordingListener();
        service = new AtelierChatService(workspaceService, messageRepository,
                (AiAgentProvider) agentProvider, byokKeyService, quotaService, modelCatalog,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, gate, auditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                // Plafond d'étapes par défaut (30) sauf mention contraire du test (SF-28-19).
                new AtelierProperties(null, null, null, null, null, null, null));

        when(modelCatalog.defaultModel()).thenReturn("claude-model");
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of());
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        when(runnerToolGateway.listFiles(eq(workspaceId), anyString())).thenReturn(ok(""));
        when(runnerToolGateway.readFile(eq(workspaceId), anyString(), anyString())).thenReturn(ok(""));
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false);
    }

    private static RunnerCallResult bashOk(String streamed, int exitCode) {
        return new RunnerCallResult(true, "", false, exitCode, 12L, null, null, null, streamed, false);
    }

    private Workspace stubWorkspace(WorkspaceExecutionTarget target) {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(target);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        return workspace;
    }

    // ------------------------------------------------------------ validation (D7)

    @Test
    void anAuthorisedCommandReachesTheMachineAndIsAudited() {
        stubWorkspace(WorkspaceExecutionTarget.RUNNER);
        listener.answer(true, null);
        when(runnerToolGateway.bash(eq(workspaceId), anyString(), eq("npm test"), any(), anyLong(), any()))
                .thenReturn(bashOk("ok\n", 0));
        agentProvider.enqueueToolCall("bash", "command", "npm test");
        agentProvider.enqueueFinal("Fait.");

        service.chatStreaming(userId, workspaceId, "lance les tests", listener);

        verify(runnerToolGateway).bash(eq(workspaceId), anyString(), eq("npm test"), any(), anyLong(), any());
        assertThat(listener.requests).extracting(AtelierConfirmRequest::tool).containsExactly("bash");
        assertThat(listener.requests.get(0).detail()).isEqualTo("npm test");
        assertThat(listener.resolved).extracting(AtelierConfirmResolved::decision).containsExactly("allow");
        // Corrélation : la demande, la trame et la ligne d'audit portent le MÊME identifiant.
        assertThat(listener.requests.get(0).toolUseId()).isEqualTo(listener.resolved.get(0).toolUseId());
        verify(auditService).recordCall(eq(userId), eq(workspaceId),
                eq(listener.requests.get(0).toolUseId()), eq("bash"), eq("npm test"), any());
    }

    @Test
    void aRefusedCommandNeverReachesTheMachine() {
        stubWorkspace(WorkspaceExecutionTarget.RUNNER);
        listener.answer(false, "trop risqué");
        agentProvider.enqueueToolCall("bash", "command", "rm -rf build");
        agentProvider.enqueueFinal("Compris.");

        service.chatStreaming(userId, workspaceId, "nettoie", listener);

        verify(runnerToolGateway, never()).bash(any(), anyString(), anyString(), any(), anyLong(), any());
        assertThat(lastToolResult().isError()).isTrue();
        assertThat(lastToolResult().content())
                .isEqualTo("Commande refusée par l'utilisateur. Motif : trop risqué");
        assertThat(listener.resolved).extracting(AtelierConfirmResolved::decision).containsExactly("deny");
        verify(auditService).recordDenied(eq(userId), eq(workspaceId), anyString(), eq("bash"),
                eq("rm -rf build"), eq(RunnerAuditOutcome.DENIED));
    }

    @Test
    void silenceRefusesTheCommandAndIsAuditedAsATimeout() {
        stubWorkspace(WorkspaceExecutionTarget.RUNNER);
        listener.answerNothing();
        agentProvider.enqueueToolCall("bash", "command", "curl http://exemple");
        agentProvider.enqueueFinal("Compris.");

        service.chatStreaming(userId, workspaceId, "appelle", listener);

        verify(runnerToolGateway, never()).bash(any(), anyString(), anyString(), any(), anyLong(), any());
        assertThat(lastToolResult().isError()).isTrue();
        assertThat(lastToolResult().content())
                .isEqualTo("Commande refusée : aucune autorisation n'a été donnée dans le délai imparti.");
        assertThat(listener.resolved).extracting(AtelierConfirmResolved::decision).containsExactly("timeout");
        verify(auditService).recordDenied(eq(userId), eq(workspaceId), anyString(), eq("bash"),
                anyString(), eq(RunnerAuditOutcome.TIMEOUT));
    }

    @Test
    void readingAndWritingAreNotHeldBehindAPrompt() {
        stubWorkspace(WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.readFile(eq(workspaceId), anyString(), eq("src/a.ts")))
                .thenReturn(ok("const x = 1;"));
        when(runnerToolGateway.writeFile(eq(workspaceId), anyString(), eq("src/a.ts"), anyString()))
                .thenReturn(ok(""));
        agentProvider.enqueueToolCall("read_file", "path", "src/a.ts");
        agentProvider.enqueueToolCall("write_file", "path", "src/a.ts", "content", "const x = 2;");
        agentProvider.enqueueFinal("Fait.");

        service.chatStreaming(userId, workspaceId, "édite", listener);

        assertThat(listener.requests).isEmpty();
        verify(auditService).recordCall(eq(userId), eq(workspaceId), anyString(), eq("read_file"),
                eq("src/a.ts"), any());
        verify(auditService).recordCall(eq(userId), eq(workspaceId), anyString(), eq("write_file"),
                eq("src/a.ts"), any());
    }

    @Test
    void sandboxTargetKeepsTheLoopUnchanged() {
        stubWorkspace(WorkspaceExecutionTarget.SANDBOX);
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of("src/a.ts"));
        when(workspaceService.readFile(userId, workspaceId, "src/a.ts")).thenReturn("const x = 1;");
        agentProvider.enqueueToolCall("read_file", "path", "src/a.ts");
        agentProvider.enqueueFinal("Lu.");

        service.chatStreaming(userId, workspaceId, "lis", listener);

        assertThat(listener.requests).isEmpty();
        assertThat(listener.resolved).isEmpty();
        // Aucune trace runner : rien ne s'est passé sur une machine (non-régression du mode sandbox).
        verify(auditService, never()).recordCall(any(), any(), anyString(), anyString(), any(), any());
        verify(auditService, never()).recordBootstrap(any(), any(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyLong());
    }

    // ------------------------------------------------------------ traçabilité (D11)

    @Test
    void bootstrapReadsAreAggregatedIntoASingleAuditLine() {
        stubWorkspace(WorkspaceExecutionTarget.RUNNER);
        // La consigne système relit CLAUDE.md + tous les skills à CHAQUE message : une ligne par
        // fichier noierait le journal sous des dizaines d'entrées non demandées.
        when(runnerToolGateway.listFiles(eq(workspaceId), anyString()))
                .thenReturn(ok(".claude/skills/a.md\n.claude/skills/b.md\nsrc/a.ts"));
        when(runnerToolGateway.readFile(eq(workspaceId), anyString(), anyString())).thenReturn(ok("x"));
        agentProvider.enqueueFinal("Bonjour.");

        service.chat(userId, workspaceId, "salut");

        ArgumentCaptor<Integer> reads = ArgumentCaptor.forClass(Integer.class);
        verify(auditService).recordBootstrap(eq(userId), eq(workspaceId), anyString(),
                reads.capture(), anyLong());
        assertThat(reads.getValue()).isEqualTo(4); // CLAUDE.md + listage + 2 skills
        // Et surtout : aucune ligne d'appel pour ces lectures d'amorçage.
        verify(auditService, never()).recordCall(any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void aFailedCallIsAuditedToo() {
        stubWorkspace(WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.readFile(eq(workspaceId), anyString(), eq("absent.txt")))
                .thenReturn(new RunnerCallResult(false, "", false, null, 3L, null, "not_found",
                        "Fichier introuvable : absent.txt", "", false));
        agentProvider.enqueueToolCall("read_file", "path", "absent.txt");
        agentProvider.enqueueFinal("Rien trouvé.");

        service.chat(userId, workspaceId, "lis absent.txt");

        verify(auditService).recordCall(eq(userId), eq(workspaceId), anyString(), eq("read_file"),
                eq("absent.txt"), any());
        assertThat(lastToolResult().isError()).isTrue();
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

    /**
     * Écran de test : enregistre les demandes d'autorisation et y répond <b>sur place</b>. La
     * demande est enregistrée par la porte <i>avant</i> d'être relayée, donc répondre depuis le
     * relais est légitime — et rend le test déterministe, sans thread ni attente.
     */
    private final class RecordingListener implements AtelierProgressListener {

        private final List<AtelierConfirmRequest> requests = new ArrayList<>();
        private final List<AtelierConfirmResolved> resolved = new ArrayList<>();
        private Boolean decision;
        private String reason;

        void answer(boolean allow, String motif) {
            this.decision = allow;
            this.reason = motif;
        }

        /** Personne ne répond : la demande doit expirer en refus. */
        void answerNothing() {
            this.decision = null;
        }

        @Override
        public void onAction(AtelierStepEvent step) {
            // Sans objet ici.
        }

        @Override
        public void onText(String text) {
            // Sans objet ici.
        }

        @Override
        public void onConfirmRequest(AtelierConfirmRequest request) {
            requests.add(request);
            if (decision != null) {
                service.confirmToolUse(userId, workspaceId, request.toolUseId(), decision, reason);
            }
        }

        @Override
        public void onConfirmResolved(AtelierConfirmResolved event) {
            resolved.add(event);
        }
    }
}
