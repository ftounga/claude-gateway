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
 * Regarder une vidéo (F-190 / SF-190-01) : {@code video_images} et {@code video_images_status}
 * déclarés seulement si le poste annonce la capacité {@code video}, jamais sur un terminal hébergé,
 * et relayés tels quels au runner.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceVideoToolsTest {

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

    @Test
    void videoToolsAreDeclaredOnlyWhenTheHostAnnouncesTheCapability() {
        Workspace workspace = runnerWorkspace();
        videoSupported(true);
        List<String> withCap = service.buildTools(userId, workspace).stream().map(AgentTool::name).toList();
        assertThat(withCap).contains("video_images", "video_images_status");

        videoSupported(false);
        List<String> withoutCap = service.buildTools(userId, workspace).stream().map(AgentTool::name).toList();
        assertThat(withoutCap).doesNotContain("video_images", "video_images_status");
    }

    @Test
    void videoToolsAreNeverDeclaredOnAHostedTerminal() {
        Workspace workspace = runnerWorkspace();
        workspace.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        videoSupported(true);
        List<String> tools = service.buildTools(userId, workspace).stream().map(AgentTool::name).toList();
        assertThat(tools).doesNotContain("video_images", "video_images_status");
    }

    @Test
    void theVideoToolTellsTheModelHowToSeeAndThatFfmpegComesByItself() {
        Workspace workspace = runnerWorkspace();
        videoSupported(true);
        AgentTool images = service.buildTools(userId, workspace).stream()
                .filter(t -> t.name().equals("video_images")).findFirst().orElseThrow();
        assertThat(images.description()).contains("read_file").contains("rapatrié tout seul")
                .contains("video_images_status");
        AgentTool status = service.buildTools(userId, workspace).stream()
                .filter(t -> t.name().equals("video_images_status")).findFirst().orElseThrow();
        assertThat(status.description()).contains("PLANCHES CONTACT").contains("Ouvre d'abord les planches");
        // SF-190-03 : le son à côté de l'image.
        assertThat(images.inputSchema().toString()).contains("transcript").contains("transcribe");
    }

    @Test
    void videoCallsAreRelayedToTheRunnerAndTheirResultReachesTheModel() {
        runnerWorkspace();
        videoSupported(true);
        when(runnerToolGateway.video(eq(runnerTarget), anyString(), eq("video_images"), any()))
                .thenReturn(ok("job_id : 0123456789abcdef\nétape : Extraction des images"));
        when(runnerToolGateway.video(eq(runnerTarget), anyString(), eq("video_images_status"), any()))
                .thenReturn(ok("job_id : 0123456789abcdef\nétape : Terminé\n00:00:12  .atelier/video/demo/frame-00001.jpg"));
        agentProvider.enqueueToolCallWithObject("video_images",
                "{\"path\":\".atelier/entrees/demo.mp4\"}");
        agentProvider.enqueueToolCallWithObject("video_images_status",
                "{\"job_id\":\"0123456789abcdef\"}");
        agentProvider.enqueueFinal("J'ai les images.");

        service.chat(userId, workspaceId, "regarde la vidéo");

        ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> input =
                ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(runnerToolGateway).video(eq(runnerTarget), anyString(), eq("video_images"), input.capture());
        assertThat(input.getValue().path("path").asText()).isEqualTo(".atelier/entrees/demo.mp4");
        assertThat(lastToolResultText()).contains("frame-00001.jpg");
    }

    // --------------------------------------------------------------- SF-190-05 : le suivi à l'écran

    @Test
    void theVideoStepSaysModeFileAndSound() {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(service.videoStep(json.createObjectNode().put("path", ".atelier/entrees/Demo Monday.mp4")))
                .isEqualTo("extraction des plans · Demo Monday.mp4");
        assertThat(service.videoStep(json.createObjectNode().put("path", "C:\\dev\\demo.mov")
                .put("mode", "intervalle").put("from", "00:12:30").put("to", "00:14:00")
                .put("every_seconds", 5).put("transcript", "demo.vtt")))
                .isEqualTo("zoom 00:12:30 → 00:14:00, toutes les 5 s + son · demo.mov");
        assertThat(service.videoStep(json.createObjectNode().put("transcribe", true)))
                .isEqualTo("extraction des plans + son · (vidéo non précisée)");
    }

    @Test
    void videoCallsEmitTheirStepsBeforeRunning() {
        runnerWorkspace();
        videoSupported(true);
        when(runnerToolGateway.video(eq(runnerTarget), anyString(), anyString(), any()))
                .thenReturn(ok("job_id : 0123456789abcdef"));
        agentProvider.enqueueToolCallWithObject("video_images",
                "{\"path\":\".atelier/entrees/demo.mp4\"}");
        agentProvider.enqueueToolCallWithObject("video_images_status",
                "{\"job_id\":\"0123456789abcdef\"}");
        agentProvider.enqueueFinal("Vu.");
        java.util.List<AtelierProgressListener.AtelierStepEvent> steps = new java.util.ArrayList<>();

        service.chatStreaming(userId, workspaceId, "regarde", new AtelierProgressListener() {
            @Override
            public void onAction(AtelierStepEvent step) {
                steps.add(step);
            }

            @Override
            public void onText(String text) {
            }
        });

        assertThat(steps).extracting(AtelierProgressListener.AtelierStepEvent::type)
                .contains("video");
        assertThat(steps).extracting(AtelierProgressListener.AtelierStepEvent::path)
                .contains("extraction des plans · demo.mp4", "suivi de l'extraction");
    }
}
