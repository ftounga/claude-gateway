package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.SpaceEntitlementService;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.videos.VideoToolCatalog;
import fr.claudegateway.videos.VideoToolExecutor;

/**
 * {@code video_retours} dans la boucle (F-214 / SF-214-01) : déclaré avec {@code video_publier} (même garde),
 * exécuté par la gateway sans accord (lecture seule), jamais relayé à la machine.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceVideoRetoursTest {

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
    @Mock private SpaceEntitlementService entitlements;
    @Mock private VideoToolExecutor videoExecutor;

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
        service.setVideoTools(new VideoToolCatalog(entitlements), videoExecutor);

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
    void retoursIsDeclaredWithVideoPublishUnderTheSameGuard() {
        Workspace workspace = runnerWorkspace();
        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(true);
        assertThat(service.buildTools(userId, workspace).stream().map(AgentTool::name).toList())
                .contains("video_publier", "video_retours");

        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(false);
        assertThat(service.buildTools(userId, workspace).stream().map(AgentTool::name).toList())
                .doesNotContain("video_publier", "video_retours");
    }

    @Test
    void retoursIsReadByTheGatewayWithoutConsentAndNeverRelayed() {
        runnerWorkspace();
        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(true);
        when(videoExecutor.readComments(eq(userId), any())).thenReturn(
                new VideoToolExecutor.VideoOutcome("Vidéo « Cours » — 1 retour.\n- [x] v1 00:10 (image 304) [ENVOYE] — noir",
                        false, null));
        agentProvider.enqueueToolCallWithObject("video_retours", "{\"video\":\"" + UUID.randomUUID() + "\"}");
        agentProvider.enqueueFinal("Je corrige.");

        service.chat(userId, workspaceId, "corrige la vidéo");

        verify(videoExecutor).readComments(eq(userId), any());
        verify(confirmationGate, never()).await(any(), any(), anyString(), any());
        verify(runnerToolGateway, never()).video(any(), anyString(), anyString(), any());
        assertThat(lastToolResultText()).contains("00:10 (image 304)");
    }

    @Test
    void retoursOutsideTheGuardIsRefusedWithoutReading() {
        runnerWorkspace();
        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(false);
        agentProvider.enqueueToolCallWithObject("video_retours", "{\"video\":\"" + UUID.randomUUID() + "\"}");
        agentProvider.enqueueFinal("Tant pis.");

        service.chat(userId, workspaceId, "corrige la vidéo");

        verify(videoExecutor, never()).readComments(any(), any());
    }

    @Test
    void offensiveModePublishesTheVideoWithoutConsent() {
        // F-218 / SF-218-02 : publier une vidéo privée ne demande plus d'accord (mode offensif, défaut).
        runnerWorkspace();
        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(true);
        when(videoExecutor.publishVideo(eq(userId), any(), anyString(), any()))
                .thenReturn(new VideoToolExecutor.VideoOutcome("Vidéo publiée.", false, null));
        agentProvider.enqueueToolCallWithObject("video_publier", "{\"path\":\"cours.mp4\",\"titre\":\"Cours\"}");
        agentProvider.enqueueFinal("Publiée.");

        service.chat(userId, workspaceId, "publie la vidéo");

        verify(videoExecutor).publishVideo(eq(userId), any(), anyString(), any());
        verify(confirmationGate, never()).await(any(), any(), anyString(), any());
    }

    @Test
    void theCircuitBreakerBringsBackTheVideoConsent() {
        // F-218 / SF-218-02 : app.atelier.offensive-mode=false ⇒ l'accord d'un clic d'avant revient.
        service.setOffensiveMode(false);
        runnerWorkspace();
        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(true);
        when(videoExecutor.publishVideo(eq(userId), any(), anyString(), any()))
                .thenReturn(new VideoToolExecutor.VideoOutcome("Vidéo publiée.", false, null));
        agentProvider.enqueueToolCallWithObject("video_publier", "{\"path\":\"cours.mp4\",\"titre\":\"Cours\"}");
        agentProvider.enqueueFinal("Publiée.");

        service.chat(userId, workspaceId, "publie la vidéo");

        verify(confirmationGate).await(any(), any(), anyString(), any());
        verify(videoExecutor).publishVideo(eq(userId), any(), anyString(), any());
    }
}
