package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.besoin.AppRequestBlock;
import fr.claudegateway.atelier.besoin.AppRequestService;
import fr.claudegateway.billing.AdministratorEntitlement;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * F-205 / SF-205-01 — l'outil {@code signaler_besoin} dans la boucle : offert à l'administrateur hors
 * Teams seulement, doctrine injectée aux mêmes conditions, carte relayée et rangée dans la transcription.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceAppRequestTest {

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
    @Mock private AppRequestService appRequests;
    @Mock private AdministratorEntitlement entitlement;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final List<AppRequestBlock> relayed = new ArrayList<>();
    private Workspace workspace;

    private final AtelierProgressListener listener = new AtelierProgressListener() {
        @Override
        public void onAppRequest(String toolUseId, AppRequestBlock besoin) {
            relayed.add(besoin);
        }

        @Override
        public void onAction(AtelierStepEvent step) {
        }

        @Override
        public void onText(String text) {
        }
    };

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
        service.setAppRequests(appRequests);
        service.setAdministratorEntitlement(entitlement);
        when(entitlement.isAdministrator(userId)).thenReturn(true);
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
        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
    }

    private String savedTranscripts() {
        org.mockito.ArgumentCaptor<AtelierMessage> saved = org.mockito.ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        return saved.getAllValues().stream().map(AtelierMessage::getTerminalJson)
                .filter(java.util.Objects::nonNull).reduce("", String::concat);
    }

    @Test
    void offeredToTheAdministratorWithItsDoctrine() {
        agentProvider.enqueueFinal("ok");
        service.chat(userId, workspaceId, "bonjour");

        assertThat(agentProvider.lastRequest.tools()).extracting(AgentTool::name)
                .contains(AtelierChatService.APP_REQUEST_TOOL_NAME);
        assertThat(agentProvider.lastRequest.system()).contains(AtelierChatService.APP_REQUEST_DOCTRINE);
    }

    @Test
    void notOfferedToANonAdministrator() {
        when(entitlement.isAdministrator(userId)).thenReturn(false);
        agentProvider.enqueueFinal("ok");
        service.chat(userId, workspaceId, "bonjour");

        assertThat(agentProvider.lastRequest.tools()).extracting(AgentTool::name)
                .doesNotContain(AtelierChatService.APP_REQUEST_TOOL_NAME);
        assertThat(agentProvider.lastRequest.system()).doesNotContain(AtelierChatService.APP_REQUEST_DOCTRINE);
    }

    @Test
    void notOfferedInTheTeamsTerminal() {
        workspace.setTeamsTerminal(true);
        agentProvider.enqueueFinal("ok");
        service.chat(userId, workspaceId, "bonjour");

        assertThat(agentProvider.lastRequest.tools()).extracting(AgentTool::name)
                .doesNotContain(AtelierChatService.APP_REQUEST_TOOL_NAME);
        assertThat(agentProvider.lastRequest.system()).doesNotContain(AtelierChatService.APP_REQUEST_DOCTRINE);
    }

    @Test
    void theSystemPromptIsIdenticalFromOneTurnToTheNext() {
        agentProvider.enqueueFinal("ok");
        service.chat(userId, workspaceId, "premier");
        String first = agentProvider.lastRequest.system();
        agentProvider.enqueueFinal("ok");
        service.chat(userId, workspaceId, "second");

        assertThat(agentProvider.lastRequest.system()).isEqualTo(first);
    }

    @Test
    void theCardIsRelayedAndKeptInTheTranscript() {
        UUID requestId = UUID.randomUUID();
        when(appRequests.propose(eq(userId), eq(workspace), any(), eq("BUG"), eq("Le terminal se fige"),
                eq("le terminal se fige quand je colle une image"), any(), any(), any()))
                .thenReturn(new AppRequestBlock(requestId, "BUG", "Le terminal se fige",
                        "le terminal se fige quand je colle une image", null, null, List.of()));
        agentProvider.enqueueToolCall(AtelierChatService.APP_REQUEST_TOOL_NAME, "type", "BUG",
                "titre", "Le terminal se fige", "description", "le terminal se fige quand je colle une image");
        agentProvider.enqueueFinal("Carte posée.");

        service.chatStreaming(userId, workspaceId, "le terminal se fige quand je colle une image", listener);

        assertThat(relayed).extracting(AppRequestBlock::requestId).containsExactly(requestId);
        assertThat(savedTranscripts()).contains("\"besoin\"").contains(requestId.toString());
    }

    @Test
    void aForcedCallByANonAdministratorIsRefused() {
        when(entitlement.isAdministrator(userId)).thenReturn(false);
        agentProvider.enqueueToolCall(AtelierChatService.APP_REQUEST_TOOL_NAME, "type", "BUG",
                "titre", "x", "description", "y");
        agentProvider.enqueueFinal("ok");

        service.chatStreaming(userId, workspaceId, "bug", listener);

        verify(appRequests, never()).propose(any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(relayed).isEmpty();
    }
}
