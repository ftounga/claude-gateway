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
import java.util.Set;
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
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * La mesure par tour (F-188 / SF-188-03) : la forme du tour arrive en base — passes, contexte,
 * réécritures du cache, refus et attentes du poste — et une panne d'écriture ne touche pas le tour.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceTurnMeasureTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    @Mock private RunnerConfirmationGate confirmationGate;
    @Mock private fr.claudegateway.runner.audit.RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final fr.claudegateway.runner.channel.RunnerTarget runnerTarget =
            new fr.claudegateway.runner.channel.RunnerTarget(hostId, workspaceId, "projet");

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
            return new RunnerConfirmationGate.Outcome(RunnerConfirmationGate.Decision.ALLOW, null);
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

    private Workspace runnerWorkspace() {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setHostId(hostId);
        workspace.setProjectPath("projet");
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        workspace.setAgentAskBeforeBash(false);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        return workspace;
    }

    private void backgroundSupported(boolean supported) {
        when(runnerHostService.declaredCapabilities(hostId))
                .thenReturn(supported ? Set.of("files", "bash", "bash_background") : Set.of("files", "bash"));
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false);
    }

    private static RunnerCallResult bashOk(String streamed, int exitCode) {
        return new RunnerCallResult(true, "", false, exitCode, 12L, null, null, null, streamed, false);
    }

    private String lastToolResultText() {
        AgentContentBlock.ToolResult found = null;
        for (AgentMessage message : agentProvider.lastRequest.messages()) {
            for (AgentContentBlock block : message.content()) {
                if (block instanceof AgentContentBlock.ToolResult result) {
                    found = result;
                }
            }
        }
        assertThat(found).as("aucun tool_result transmis au modèle").isNotNull();
        return found.content();
    }

    private static fr.claudegateway.agent.AgentTurn pass(String tool, String json, int context, int cacheWrite) {
        java.util.List<fr.claudegateway.agent.AgentToolCall> calls = new java.util.ArrayList<>();
        try {
            calls.add(new fr.claudegateway.agent.AgentToolCall("toolu_" + context, tool,
                    (com.fasterxml.jackson.databind.node.ObjectNode)
                            new com.fasterxml.jackson.databind.ObjectMapper().readTree(json)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return new fr.claudegateway.agent.AgentTurn("", calls, false, context, 100, false, List.of(),
                context - cacheWrite, cacheWrite, 0, false, null, null, List.of(), null, 0);
    }

    private static fr.claudegateway.agent.AgentTurn last(int context, int cacheWrite) {
        return new fr.claudegateway.agent.AgentTurn("Fini.", List.of(), true, context, 50, false,
                List.of(), context - cacheWrite, cacheWrite, 0, false, null, null, List.of(), null, 0);
    }

    @Test
    void theShapeOfTheTurnIsMeasured() {
        runnerWorkspace();
        fr.claudegateway.atelier.measure.TurnMeasureWriter writer =
                org.mockito.Mockito.mock(fr.claudegateway.atelier.measure.TurnMeasureWriter.class);
        service.setTurnMeasureWriter(writer);
        service.setRunnerBusyWait(500L, 5L);
        when(runnerToolGateway.bash(eq(runnerTarget), anyString(), eq("refuse"), any(), anyLong(), any()))
                .thenReturn(new RunnerCallResult(false, "", false, null, 5L, null, "denied",
                        "Commande refusée.", "", false));
        when(runnerToolGateway.bash(eq(runnerTarget), anyString(), eq("attends"), any(), anyLong(), any()))
                .thenReturn(new RunnerCallResult(false, "", false, null, 5L, null, "denied",
                                AtelierChatService.RUNNER_BUSY_MESSAGE, "", false),
                        new RunnerCallResult(true, "", false, 0, 5L, null, null, null, "ok", false));
        agentProvider.enqueueTurn(pass("bash", "{\"command\":\"refuse\"}", 100_000, 20_000));
        agentProvider.enqueueTurn(pass("bash", "{\"command\":\"attends\"}", 130_000, 4_000));
        agentProvider.enqueueTurn(last(120_000, 1_000));

        service.chat(userId, workspaceId, "mesure");

        ArgumentCaptor<fr.claudegateway.atelier.measure.TurnMeasure> written =
                ArgumentCaptor.forClass(fr.claudegateway.atelier.measure.TurnMeasure.class);
        verify(writer).write(written.capture());
        fr.claudegateway.atelier.measure.TurnMeasure m = written.getValue();
        assertThat(m.iterations()).isEqualTo(3);
        assertThat(m.maxContextTokens()).isEqualTo(130_000L);
        assertThat(m.totalContextTokens()).isEqualTo(350_000L);
        assertThat(m.firstPassCacheWrite()).isEqualTo(20_000L);
        assertThat(m.laterCacheWrite()).isEqualTo(5_000L);
        assertThat(m.runnerRefusals()).isEqualTo(1);
        assertThat(m.runnerBusyWaits()).isEqualTo(1);
        assertThat(m.stopCause()).isNotBlank();
        assertThat(m.userId()).isEqualTo(userId);
        assertThat(m.workspaceId()).isEqualTo(workspaceId);
    }

    @Test
    void aBrokenWriterLeavesTheTurnIntact() {
        runnerWorkspace();
        fr.claudegateway.atelier.measure.TurnMeasureWriter writer =
                new fr.claudegateway.atelier.measure.TurnMeasureWriter(
                        new org.springframework.jdbc.core.JdbcTemplate());
        service.setTurnMeasureWriter(writer);
        agentProvider.enqueueFinal("Réponse.");

        AtelierChatService.AtelierChatResult result = service.chat(userId, workspaceId, "bonjour");

        assertThat(result.reply()).contains("Réponse.");
    }
}
