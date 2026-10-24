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
 * Rendre une vidéo (F-215 / SF-215-02) : {@code video_render} et {@code video_render_status} déclarés
 * seulement si le poste annonce la capacité {@code video_render}, jamais en hébergé ; relayés au runner ;
 * {@code video_render} passe la porte de {@code bash}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceVideoRenderToolsTest {

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

    @Test
    void renderToolsAreDeclaredOnlyWithTheRenderCapability() {
        Workspace workspace = runnerWorkspace();
        renderSupported(true);
        assertThat(service.buildTools(userId, workspace).stream().map(AgentTool::name).toList())
                .contains("video_render", "video_render_status");

        // Un runner qui sait regarder une vidéo mais pas la rendre (ancien, ou commandes interdites).
        renderSupported(false);
        assertThat(service.buildTools(userId, workspace).stream().map(AgentTool::name).toList())
                .contains("video_images").doesNotContain("video_render", "video_render_status");

        videoSupported(false);
        assertThat(service.buildTools(userId, workspace).stream().map(AgentTool::name).toList())
                .doesNotContain("video_render");
    }

    @Test
    void renderToolsAreNeverDeclaredOnAHostedTerminal() {
        Workspace workspace = runnerWorkspace();
        workspace.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        renderSupported(true);
        assertThat(service.buildTools(userId, workspace).stream().map(AgentTool::name).toList())
                .doesNotContain("video_render", "video_render_status");
    }

    @Test
    void renderCallsAreRelayedAndTheirResultReachesTheModel() {
        runnerWorkspace();
        renderSupported(true);
        when(runnerToolGateway.video(eq(runnerTarget), anyString(), eq("video_render"), any()))
                .thenReturn(ok("job_id : 0123456789ab\nétape : Préparation de la chaîne"));
        when(runnerToolGateway.video(eq(runnerTarget), anyString(), eq("video_render_status"), any()))
                .thenReturn(ok("job_id : 0123456789ab\nétape : Terminé\nvidéo : cours/renders/cours.mp4 (12.0 Mo)"));
        agentProvider.enqueueToolCallWithObject("video_render", "{\"dossier\":\"cours\",\"qualite\":\"draft\"}");
        agentProvider.enqueueToolCallWithObject("video_render_status", "{\"job_id\":\"0123456789ab\"}");
        agentProvider.enqueueFinal("Rendu terminé.");

        service.chat(userId, workspaceId, "rends la vidéo");

        ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> input =
                ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(runnerToolGateway).video(eq(runnerTarget), anyString(), eq("video_render"), input.capture());
        assertThat(input.getValue().path("dossier").asText()).isEqualTo("cours");
        assertThat(lastToolResultText()).contains("cours.mp4");
        // Le sujet ne demande pas avant les commandes : aucune invite.
        verify(confirmationGate, never()).await(any(), any(), anyString(), any());
    }

    @Test
    void renderPassesTheBashGateWhenTheSubjectAsksBeforeCommands() {
        Workspace workspace = runnerWorkspace();
        workspace.setAgentAskBeforeBash(true);
        renderSupported(true);
        when(runnerToolGateway.video(eq(runnerTarget), anyString(), anyString(), any()))
                .thenReturn(ok("job_id : 0123456789ab"));
        agentProvider.enqueueToolCallWithObject("video_render", "{\"dossier\":\"cours\"}");
        agentProvider.enqueueToolCallWithObject("video_render_status", "{\"job_id\":\"0123456789ab\"}");
        agentProvider.enqueueFinal("Lancé.");

        service.chat(userId, workspaceId, "rends la vidéo");

        // Une seule invite : le rendu ; le suivi passe sans porte.
        verify(confirmationGate, org.mockito.Mockito.times(1)).await(any(), any(), anyString(), any());
    }

    @Test
    void theDispatcherRequiresTheRenderCapabilityBeforeTheVideoPrefix() throws Exception {
        java.lang.reflect.Method capabilityFor = fr.claudegateway.runner.channel.RunnerCallDispatcher.class
                .getDeclaredMethod("capabilityFor", String.class);
        capabilityFor.setAccessible(true);
        assertThat(capabilityFor.invoke(null, "video_render")).isEqualTo("video_render");
        assertThat(capabilityFor.invoke(null, "video_render_status")).isEqualTo("video_render");
        assertThat(capabilityFor.invoke(null, "video_images")).isEqualTo("video");
    }
}
