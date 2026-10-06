package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
import fr.claudegateway.atelier.proposal.GovernanceProposalBlock;
import fr.claudegateway.atelier.proposal.GovernanceProposalService;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.channel.RunnerCallResult;

/**
 * F-177 / SF-177-02 — l'outil {@code gouvernance_proposer} dans la boucle : offert hors Teams, la carte
 * relayée et rangée dans la transcription, la doctrine injectée, et le rappel sur un write_file hors
 * circuit.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceGovernanceProposalTest {

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
    @Mock private GovernanceProposalService proposals;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final List<GovernanceProposalBlock> relayed = new ArrayList<>();
    private Workspace workspace;

    private final AtelierProgressListener listener = new AtelierProgressListener() {
        @Override
        public void onGovernanceProposal(String toolUseId, GovernanceProposalBlock proposal) {
            relayed.add(proposal);
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
        service.setGovernanceProposals(proposals);
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
    void theToolIsOfferedAndTheDoctrineInjected() {
        agentProvider.enqueueFinal("ok");
        service.chat(userId, workspaceId, "bonjour");

        assertThat(agentProvider.lastRequest.tools()).extracting(AgentTool::name)
                .contains(AtelierChatService.GOVERNANCE_PROPOSE_TOOL_NAME);
        assertThat(agentProvider.lastRequest.system()).contains(AtelierChatService.GOVERNANCE_PROPOSAL_DOCTRINE);
    }

    @Test
    void notOfferedInTheTeamsTerminal() {
        workspace.setTeamsTerminal(true);
        agentProvider.enqueueFinal("ok");
        service.chat(userId, workspaceId, "bonjour");

        assertThat(agentProvider.lastRequest.tools()).extracting(AgentTool::name)
                .doesNotContain(AtelierChatService.GOVERNANCE_PROPOSE_TOOL_NAME);
        assertThat(agentProvider.lastRequest.system()).doesNotContain(AtelierChatService.GOVERNANCE_PROPOSAL_DOCTRINE);
    }

    @Test
    void aProposalIsRelayedAndKeptInTheTranscript() {
        UUID proposalId = UUID.randomUUID();
        when(proposals.propose(eq(userId), eq(workspace), eq("REGLE"), eq("SUJET"), eq("Jira"),
                eq("Commentaires Jira courts."), any()))
                .thenReturn(new GovernanceProposalBlock(proposalId, "REGLE", "SUJET", "Jira", "GOUVERNANCE.md",
                        null, true, List.of(new GovernanceProposalBlock.DiffLine("ADD", "## Jira")), 0));
        agentProvider.enqueueToolCall(AtelierChatService.GOVERNANCE_PROPOSE_TOOL_NAME, "type", "REGLE",
                "portee", "SUJET", "nom", "Jira", "contenu", "Commentaires Jira courts.");
        agentProvider.enqueueFinal("Proposition posée : clique Appliquer.");

        service.chatStreaming(userId, workspaceId, "mets dans la gouvernance : commentaires Jira courts", listener);

        assertThat(relayed).extracting(GovernanceProposalBlock::proposalId).containsExactly(proposalId);
        assertThat(savedTranscripts()).contains("\"proposal\"").contains(proposalId.toString());
    }

    @Test
    void aDirectWriteToGovernanceFilesGetsTheReminder() {
        when(workspaceService.readFile(any(), any(), anyString())).thenThrow(new RuntimeException("absent"));
        agentProvider.enqueueToolCall("write_file", "path", ".claude/skills/jira.md", "content", "x");
        agentProvider.enqueueFinal("fait");

        service.chatStreaming(userId, workspaceId, "crée un skill", listener);

        String lastToolResult = String.valueOf(agentProvider.lastRequest.messages());
        assertThat(lastToolResult).contains("passe par la proposition");
        verify(proposals, never()).propose(any(), any(), any(), any(), any(), any(), any());
    }

    @SuppressWarnings("unused")
    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false);
    }
}
