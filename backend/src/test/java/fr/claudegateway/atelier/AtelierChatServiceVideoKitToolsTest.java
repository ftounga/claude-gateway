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
 * Le savoir-faire « cours vidéo » (F-215 / SF-215-03) : {@code video_briques} et {@code video_render_check}
 * déclarés avec la capacité {@code video_render}, jamais en hébergé ; une seule ligne FIXE de doctrine, jamais
 * le guide lui-même ; la relecture relayée et soumise à la porte de {@code bash}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceVideoKitToolsTest {

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

    private void videoSupported(boolean supported) {
        when(runnerHostService.declaredCapabilities(hostId))
                .thenReturn(supported ? Set.of("files", "bash", "video") : Set.of("files", "bash"));
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false);
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

    private void renderSupported(boolean supported) {
        when(runnerHostService.declaredCapabilities(hostId))
                .thenReturn(supported ? Set.of("files", "bash", "video", "video_render") : Set.of("files", "bash", "video"));
    }

    private fr.claudegateway.atelier.video.VideoKitToolExecutor kitExecutor() {
        fr.claudegateway.atelier.video.VideoKitToolExecutor executor =
                org.mockito.Mockito.mock(fr.claudegateway.atelier.video.VideoKitToolExecutor.class);
        service.setVideoKitTool(executor);
        return executor;
    }

    @Test
    void kitAndCheckAreDeclaredOnlyWithTheRenderCapability() {
        Workspace workspace = runnerWorkspace();
        kitExecutor();
        renderSupported(true);
        assertThat(service.buildTools(userId, workspace).stream().map(AgentTool::name).toList())
                .contains("video_briques", "video_render_check");

        renderSupported(false);
        assertThat(service.buildTools(userId, workspace).stream().map(AgentTool::name).toList())
                .doesNotContain("video_briques", "video_render_check");

        workspace.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        renderSupported(true);
        assertThat(service.buildTools(userId, workspace).stream().map(AgentTool::name).toList())
                .doesNotContain("video_briques", "video_render_check");
    }

    @Test
    void withoutTheKitExecutorTheToolIsNeverGiven() {
        Workspace workspace = runnerWorkspace();
        renderSupported(true);
        assertThat(service.buildTools(userId, workspace).stream().map(AgentTool::name).toList())
                .contains("video_render_check").doesNotContain("video_briques");
    }

    @Test
    void theSystemPromptCarriesOneFixedLineAndNeverTheGuide() {
        runnerWorkspace();
        kitExecutor();
        renderSupported(true);
        agentProvider.enqueueFinal("ok");

        service.chat(userId, workspaceId, "bonjour");

        String system = agentProvider.lastRequest.system();
        assertThat(system).contains(fr.claudegateway.atelier.video.VideoKitToolCatalog.DOCTRINE);
        assertThat(system.split(java.util.regex.Pattern.quote("Vidéo ou cours vidéo demandé"), -1)).hasSize(2);
        // Le guide est lu à la demande : aucun de ses passages n'entre dans le préfixe.
        assertThat(system).doesNotContain("Pièges connus").doesNotContain("Narration par chapitre")
                .doesNotContain("data-composition-id");
    }

    @Test
    void noDoctrineLineWithoutTheRenderCapability() {
        runnerWorkspace();
        kitExecutor();
        renderSupported(false);
        agentProvider.enqueueFinal("ok");

        service.chat(userId, workspaceId, "bonjour");

        assertThat(agentProvider.lastRequest.system()).doesNotContain("video_briques");
    }

    @Test
    void theKitIsExecutedByTheGatewayNeverRelayed() {
        runnerWorkspace();
        fr.claudegateway.atelier.video.VideoKitToolExecutor executor = kitExecutor();
        renderSupported(true);
        when(executor.execute(eq(userId), any(), anyString(), any())).thenReturn(
                new fr.claudegateway.atelier.video.VideoKitToolExecutor.Outcome("Déposés : guide et briques.", false));
        agentProvider.enqueueToolCallWithObject("video_briques", "{\"dossier\":\"video/cours\"}");
        agentProvider.enqueueFinal("Prêt.");

        service.chat(userId, workspaceId, "fais une vidéo");

        verify(executor).execute(eq(userId), any(), anyString(), any());
        verify(runnerToolGateway, never()).video(any(), anyString(), anyString(), any());
        assertThat(lastToolResultText()).contains("Déposés : guide et briques.");
    }

    @Test
    void theKitOnAnOldPosteIsRefusedWithoutExecuting() {
        runnerWorkspace();
        fr.claudegateway.atelier.video.VideoKitToolExecutor executor = kitExecutor();
        renderSupported(false);
        agentProvider.enqueueToolCallWithObject("video_briques", "{\"dossier\":\"video/cours\"}");
        agentProvider.enqueueFinal("Tant pis.");

        service.chat(userId, workspaceId, "fais une vidéo");

        verify(executor, never()).execute(any(), any(), anyString(), any());
        assertThat(lastToolResultText()).contains("mettez le poste à jour");
    }

    @Test
    void checkIsRelayedAndPassesTheBashGate() {
        Workspace workspace = runnerWorkspace();
        workspace.setAgentAskBeforeBash(true);
        renderSupported(true);
        when(runnerToolGateway.video(eq(runnerTarget), anyString(), eq("video_render_check"), any()))
                .thenReturn(ok("LINT : OK\nIMAGES (1) — ouvre CHACUNE avec read_file\n- cours/relecture/frame-00-at-3s.png"));
        agentProvider.enqueueToolCallWithObject("video_render_check", "{\"dossier\":\"cours\",\"instants\":\"3\"}");
        agentProvider.enqueueFinal("Relu.");

        service.chat(userId, workspaceId, "relis la vidéo");

        ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> input =
                ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(runnerToolGateway).video(eq(runnerTarget), anyString(), eq("video_render_check"), input.capture());
        assertThat(input.getValue().path("instants").asText()).isEqualTo("3");
        assertThat(lastToolResultText()).contains("frame-00-at-3s.png");
        verify(confirmationGate, org.mockito.Mockito.times(1)).await(any(), any(), anyString(), any());
    }

    @Test
    void theDispatcherRequiresTheRenderCapabilityForTheCheck() throws Exception {
        java.lang.reflect.Method capabilityFor = fr.claudegateway.runner.channel.RunnerCallDispatcher.class
                .getDeclaredMethod("capabilityFor", String.class);
        capabilityFor.setAccessible(true);
        assertThat(capabilityFor.invoke(null, "video_render_check")).isEqualTo("video_render");
    }
}
