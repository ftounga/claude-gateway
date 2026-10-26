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
 * {@code video_devis} dans la boucle (F-216 / SF-216-01) : déclaré avec {@code video_publier} (même garde),
 * calculé par la gateway sans accord (gratuit), jamais relayé à la machine, permis en mode planifier.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceVideoDevisTest {

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
    @Mock private fr.claudegateway.videos.VideoQuoteService quoteService;

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
        service.setVideoQuotes(quoteService);

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
    void devisIsDeclaredWithVideoPublishUnderTheSameGuard() {
        Workspace workspace = runnerWorkspace();
        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(true);
        assertThat(service.buildTools(userId, workspace).stream().map(AgentTool::name).toList())
                .contains("video_publier", "video_devis");

        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(false);
        assertThat(service.buildTools(userId, workspace).stream().map(AgentTool::name).toList())
                .doesNotContain("video_publier", "video_devis");
    }

    @Test
    void devisIsComputedByTheGatewayWithoutConsentAndNeverRelayed() {
        Workspace workspace = runnerWorkspace();
        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(true);
        when(quoteService.quote(eq(userId), eq(workspace), any())).thenReturn(
                new fr.claudegateway.videos.VideoQuoteService.Outcome(
                        "Devis x — vidéo unique de 2 min 30.\nCoût estimé : 2,22 à 4,43 $ (voix 0,04 à 0,08 $ · "
                                + "agent 2,17 à 4,35 $ · rendu 0 $ — votre poste)", false, UUID.randomUUID()));
        agentProvider.enqueueToolCallWithObject("video_devis", "{\"duree_min\":2.5}");
        agentProvider.enqueueFinal("Voici la fiche et le devis.");

        service.chat(userId, workspaceId, "fais-moi une vidéo sur EKS");

        verify(quoteService).quote(eq(userId), eq(workspace), any());
        verify(confirmationGate, never()).await(any(), any(), anyString(), any());
        verify(runnerToolGateway, never()).video(any(), anyString(), anyString(), any());
        assertThat(lastToolResultText()).contains("Coût estimé : 2,22 à 4,43 $").contains("rendu 0 $ — votre poste");
    }

    @Test
    void devisOutsideTheGuardIsRefusedWithoutComputing() {
        runnerWorkspace();
        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(false);
        agentProvider.enqueueToolCallWithObject("video_devis", "{\"duree_min\":2.5}");
        agentProvider.enqueueFinal("Tant pis.");

        service.chat(userId, workspaceId, "fais-moi une vidéo");

        verify(quoteService, never()).quote(any(), any(), any());
        assertThat(lastToolResultText()).contains("ne sont pas ouvertes");
    }

    @Test
    void devisDefinitionIsFixedText() {
        Workspace workspace = runnerWorkspace();
        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(true);
        AgentTool first = devis(workspace);
        assertThat(first).isEqualTo(devis(workspace));
        assertThat(first.description()).contains("AVANT toute fabrication").contains("n'estime jamais")
                .contains("question UNIQUE").contains("sans accord");
        assertThat(VideoToolCatalog.isDevisTool("video_devis")).isTrue();
        assertThat(VideoToolCatalog.isVideoTool("video_devis")).isFalse();
    }

    private AgentTool devis(Workspace workspace) {
        return service.buildTools(userId, workspace).stream().filter(tool -> "video_devis".equals(tool.name()))
                .findFirst().orElseThrow();
    }
}
