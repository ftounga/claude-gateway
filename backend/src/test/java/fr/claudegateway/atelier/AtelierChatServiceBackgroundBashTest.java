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
 * Bash en arrière-plan (F-121 / SF-121-07) : {@code timeout} paramétrable, {@code run_in_background}
 * routé vers {@code bashBackground} quand le poste annonce {@code bash_background}, outils
 * {@code bash_output}/{@code kill_shell}, et retro-compat (capacité absente = déclaration muette,
 * lancement synchrone).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceBackgroundBashTest {

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

    // --------------------------------------------------------------- déclaration des outils

    @Test
    void backgroundToolsAreDeclaredOnlyWhenTheHostSupportsThem() {
        Workspace workspace = runnerWorkspace();
        backgroundSupported(true);
        List<String> withCap = service.buildTools(userId, workspace).stream().map(AgentTool::name).toList();
        assertThat(withCap).contains("bash", "bash_output", "kill_shell");

        backgroundSupported(false);
        List<String> withoutCap = service.buildTools(userId, workspace).stream().map(AgentTool::name).toList();
        assertThat(withoutCap).contains("bash").doesNotContain("bash_output", "kill_shell");
    }

    @Test
    void theBashToolAlwaysDeclaresATimeoutParameter() {
        Workspace workspace = runnerWorkspace();
        backgroundSupported(false);
        AgentTool bash = service.buildTools(userId, workspace).stream()
                .filter(t -> t.name().equals("bash")).findFirst().orElseThrow();
        assertThat(bash.inputSchema().toString()).contains("timeout");
        // run_in_background n'est PAS déclaré sans capacité.
        assertThat(bash.inputSchema().toString()).doesNotContain("run_in_background");
    }

    // --------------------------------------------------------------- routage à l'exécution

    @Test
    void runInBackgroundRoutesToBashBackgroundAndReturnsTheShellId() {
        runnerWorkspace();
        backgroundSupported(true);
        when(runnerToolGateway.bashBackground(eq(runnerTarget), anyString(), eq("npm run dev"), any()))
                .thenReturn(ok("Commande lancée en arrière-plan. Identifiant : bash_1."));
        agentProvider.enqueueToolCallWithObject("bash",
                "{\"command\":\"npm run dev\",\"run_in_background\":true}");
        agentProvider.enqueueFinal("Serveur lancé.");

        service.chat(userId, workspaceId, "lance le serveur");

        verify(runnerToolGateway).bashBackground(eq(runnerTarget), anyString(), eq("npm run dev"), any());
        verify(runnerToolGateway, never()).bash(any(), anyString(), anyString(), any(), anyLong(), any());
        assertThat(lastToolResultText()).contains("bash_1");
    }

    @Test
    void bashOutputAndKillShellRouteToTheirGatewayMethods() {
        runnerWorkspace();
        backgroundSupported(true);
        when(runnerToolGateway.bashOutput(eq(runnerTarget), anyString(), eq("bash_1")))
                .thenReturn(ok("ligne nouvelle\n[état: en cours]"));
        when(runnerToolGateway.killShell(eq(runnerTarget), anyString(), eq("bash_1")))
                .thenReturn(ok("Commande bash_1 arrêtée."));
        agentProvider.enqueueToolCallWithObject("bash_output", "{\"bash_id\":\"bash_1\"}");
        agentProvider.enqueueToolCallWithObject("kill_shell", "{\"shell_id\":\"bash_1\"}");
        agentProvider.enqueueFinal("Vu et arrêté.");

        service.chat(userId, workspaceId, "relis puis arrête bash_1");

        verify(runnerToolGateway).bashOutput(eq(runnerTarget), anyString(), eq("bash_1"));
        verify(runnerToolGateway).killShell(eq(runnerTarget), anyString(), eq("bash_1"));
    }

    @Test
    void runInBackgroundFallsBackToSynchronousWhenTheHostLacksTheCapability() {
        // Défensif : si le modèle force run_in_background sans capacité, on exécute en synchrone plutôt
        // que d'appeler bashBackground (qu'un runner ancien ne saurait pas honorer).
        runnerWorkspace();
        backgroundSupported(false);
        when(runnerToolGateway.bash(eq(runnerTarget), anyString(), eq("npm run dev"), any(), anyLong(), any()))
                .thenReturn(bashOk("démarrage\n", 0));
        agentProvider.enqueueToolCallWithObject("bash",
                "{\"command\":\"npm run dev\",\"run_in_background\":true}");
        agentProvider.enqueueFinal("Fait.");

        service.chat(userId, workspaceId, "lance");

        verify(runnerToolGateway).bash(eq(runnerTarget), anyString(), eq("npm run dev"), any(), anyLong(), any());
        verify(runnerToolGateway, never()).bashBackground(any(), anyString(), anyString(), any());
    }

    // --------------------------------------------------------------- timeout paramétrable

    @Test
    void aRequestedTimeoutIsPassedThroughBoundedByTheTurnBudget() {
        runnerWorkspace();
        backgroundSupported(false);
        ArgumentCaptor<Long> timeout = ArgumentCaptor.forClass(Long.class);
        when(runnerToolGateway.bash(eq(runnerTarget), anyString(), eq("sleep 3"), any(), timeout.capture(), any()))
                .thenReturn(bashOk("", 0));
        agentProvider.enqueueToolCallWithObject("bash", "{\"command\":\"sleep 3\",\"timeout\":5000}");
        agentProvider.enqueueFinal("Fini.");

        service.chat(userId, workspaceId, "attends 3s");

        // Le budget de tour (10 min) est bien supérieur : le timeout demandé passe tel quel.
        assertThat(timeout.getValue()).isEqualTo(5000L);
    }

    @Test
    void withoutATimeoutTheDefaultIsKept() {
        runnerWorkspace();
        backgroundSupported(false);
        ArgumentCaptor<Long> timeout = ArgumentCaptor.forClass(Long.class);
        when(runnerToolGateway.bash(eq(runnerTarget), anyString(), eq("ls"), any(), timeout.capture(), any()))
                .thenReturn(bashOk("", 0));
        agentProvider.enqueueToolCall("bash", "command", "ls");
        agentProvider.enqueueFinal("Fait.");

        service.chat(userId, workspaceId, "liste");

        // Défaut 120 s (borné au budget restant, ici bien supérieur).
        assertThat(timeout.getValue()).isEqualTo(RunnerToolGateway.BASH_TIMEOUT_MS);
    }
}
