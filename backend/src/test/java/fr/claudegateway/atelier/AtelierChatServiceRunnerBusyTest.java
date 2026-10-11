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
 * Poste occupé (F-188 / SF-188-02) : quand le runner refuse un {@code bash} parce qu'une commande
 * précédente tourne encore, la gateway attend et réessaie au lieu de rendre le refus au modèle, qui le
 * relançait en boucle (20 fois le 2026-10-04).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceRunnerBusyTest {

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

    private static RunnerCallResult busy() {
        return new RunnerCallResult(false, "", false, null, 5L, null, "denied",
                AtelierChatService.RUNNER_BUSY_MESSAGE, "", false);
    }

    private void bashAnswers(RunnerCallResult first, RunnerCallResult... next) {
        when(runnerToolGateway.bash(eq(runnerTarget), anyString(), anyString(), any(), anyLong(), any()))
                .thenReturn(first, next);
    }

    private void askForOneCommand() {
        agentProvider.enqueueToolCallWithObject("bash", "{\"command\":\"terraform plan\"}");
        agentProvider.enqueueFinal("Fait.");
    }

    @Test
    void aBusyPosteIsAwaitedAndTheRealResultReachesTheModel() {
        runnerWorkspace();
        service.setRunnerBusyWait(500L, 10L);
        bashAnswers(busy(), busy(), bashOk("No changes. Infrastructure is up-to-date.", 0));
        askForOneCommand();

        service.chat(userId, workspaceId, "plan");

        verify(runnerToolGateway, org.mockito.Mockito.times(3))
                .bash(eq(runnerTarget), anyString(), eq("terraform plan"), any(), anyLong(), any());
        assertThat(lastToolResultText()).contains("No changes.").doesNotContain("déjà en cours");
    }

    @Test
    void aPosteStillBusyAtTheBoundExplainsInsteadOfABareRefusal() {
        runnerWorkspace();
        service.setRunnerBusyWait(60L, 10L);
        when(runnerToolGateway.bash(eq(runnerTarget), anyString(), anyString(), any(), anyLong(), any()))
                .thenReturn(busy());
        askForOneCommand();

        service.chat(userId, workspaceId, "plan");

        assertThat(lastToolResultText())
                .contains("Une commande précédente tourne encore sur le poste")
                .contains("Ne relance pas la même commande à l'identique")
                .contains("run_in_background");
    }

    @Test
    void anotherDenialIsReturnedAsIsWithoutWaiting() {
        runnerWorkspace();
        service.setRunnerBusyWait(500L, 10L);
        bashAnswers(new RunnerCallResult(false, "", false, null, 5L, null, "denied",
                "Commande refusée par l'utilisateur.", "", false));
        askForOneCommand();

        service.chat(userId, workspaceId, "plan");

        verify(runnerToolGateway, org.mockito.Mockito.times(1))
                .bash(eq(runnerTarget), anyString(), anyString(), any(), anyLong(), any());
        assertThat(lastToolResultText()).doesNotContain("tourne encore");
    }

    @Test
    void zeroDisablesTheWaitAsACircuitBreaker() {
        runnerWorkspace();
        service.setRunnerBusyWait(0L, 10L);
        bashAnswers(busy(), bashOk("jamais atteint", 0));
        askForOneCommand();

        service.chat(userId, workspaceId, "plan");

        verify(runnerToolGateway, org.mockito.Mockito.times(1))
                .bash(eq(runnerTarget), anyString(), anyString(), any(), anyLong(), any());
        assertThat(lastToolResultText()).contains("tourne encore");
    }

    @Test
    void onlyAFreshBusyBashIsRecognised() {
        AgentToolCallFixture fixture = new AgentToolCallFixture();
        assertThat(AtelierChatService.isRunnerBusy(fixture.call("bash"), busy())).isTrue();
        assertThat(AtelierChatService.isRunnerBusy(fixture.call("read_file"), busy())).isFalse();
        assertThat(AtelierChatService.isRunnerBusy(fixture.call("bash"), ok("sortie"))).isFalse();
    }

    /** Fabrique d'appels d'outil sans entrée. */
    private static final class AgentToolCallFixture {
        fr.claudegateway.agent.AgentToolCall call(String name) {
            return new fr.claudegateway.agent.AgentToolCall("toolu_1", name,
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        }
    }
}
