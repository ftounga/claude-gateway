package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentReasoning;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * Raisonnement de la boucle maison (F-39 / SF-39-10) : le modèle est celui du harnais, le tour
 * demande un raisonnement adaptatif, et les blocs signés rendus par le fournisseur sont remis en
 * tête du message assistant — mais ne survivent pas au tour.
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceReasoningTest {

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

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final List<AtelierMessage> history = new ArrayList<>();
    private final List<AtelierMessage> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        buildService(new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null));

        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        // Quota lu pour dériver le plafond de consommation du message (F-39 / SF-39-15).
        org.mockito.Mockito.lenient().when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        lenient().when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(history);
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage message = invocation.getArgument(0);
            if (message.getId() == null) {
                message.setId(UUID.randomUUID());
            }
            saved.add(message);
            return message;
        });
        lenient().when(workspaceService.tree(any(), any())).thenReturn(List.of());
        lenient().when(workspaceService.readFile(any(), any(), any())).thenReturn("contenu");
    }

    private void buildService(AtelierProperties properties) {
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                properties);
    }

    private AtelierMessage lastSavedAssistant() {
        return saved.stream().filter(m -> "ASSISTANT".equals(m.getRole())).reduce((a, b) -> b).orElseThrow();
    }

    @Test
    void sendsTheHarnessModelAndAsksForAdaptiveReasoning() {
        agentProvider.enqueueFinal("Bonjour.");

        service.chat(userId, workspaceId, "bonjour");

        // Le modèle de la boucle est le sien (D-L5-1) : plus celui que le chat propose par défaut.
        assertThat(agentProvider.lastRequest.model()).isEqualTo("claude-opus-5");
        assertThat(agentProvider.lastRequest.reasoning()).isEqualTo(new AgentReasoning(true, "high"));
    }

    @Test
    void honoursTheConfiguredModelAndEffort() {
        buildService(new AtelierProperties(null, null, null, null, null, null, null,
                "claude-opus-4-8", "xhigh", null, null));
        agentProvider.enqueueFinal("Bonjour.");

        service.chat(userId, workspaceId, "bonjour");

        assertThat(agentProvider.lastRequest.model()).isEqualTo("claude-opus-4-8");
        assertThat(agentProvider.lastRequest.reasoning()).isEqualTo(new AgentReasoning(true, "xhigh"));
    }

    @Test
    void replaysTheReasoningOfTheTurnAheadOfItsTextAndToolCalls() {
        agentProvider.enqueueToolCallWithReasoning("read_file", "sig-1", "path", "notes.txt");
        agentProvider.enqueueFinal("J'ai lu notes.txt.");

        service.chat(userId, workspaceId, "lis notes.txt");

        // Le fournisseur exige de retrouver ses blocs signés, inchangés et EN TÊTE, sur le dernier
        // tour d'assistant quand on lui renvoie les tool_result (D-L5-3).
        List<AgentMessage> sent = agentProvider.lastRequest.messages();
        AgentMessage assistant = sent.get(sent.size() - 2);
        assertThat(assistant.role()).isEqualTo("assistant");
        assertThat(assistant.content().get(0))
                .isEqualTo(new AgentContentBlock.Reasoning("", "sig-1"));
        assertThat(assistant.content().get(1)).isEqualTo(new AgentContentBlock.Text("je regarde"));
        assertThat(assistant.content().get(2)).isInstanceOf(AgentContentBlock.ToolUse.class);
    }

    @Test
    void doesNotKeepTheReasoningBeyondTheTurn() {
        agentProvider.enqueueToolCallWithReasoning("read_file", "sig-1", "path", "notes.txt");
        agentProvider.enqueueFinal("J'ai lu notes.txt.");

        service.chat(userId, workspaceId, "lis notes.txt");

        // Le raisonnement vit le temps d'un tour : la trajectoire persistée n'en garde rien, donc le
        // message suivant ne peut pas rejouer un bloc signé hors de son contexte.
        String trace = lastSavedAssistant().getToolTrace();
        assertThat(trace).doesNotContain("sig-1").doesNotContain("thinking");
    }

    @Test
    void aReplayedHistoryCarriesNoReasoningBlock() {
        history.add(AtelierMessage.builder().id(UUID.randomUUID()).workspaceId(workspaceId).userId(userId)
                .role("USER").content("lis notes.txt").build());
        history.add(AtelierMessage.builder().id(UUID.randomUUID()).workspaceId(workspaceId).userId(userId)
                .role("ASSISTANT").content("J'ai lu notes.txt.")
                .toolTrace(new AtelierToolTrace(List.of(new AtelierToolTrace.Step("je lis",
                        List.of(new AtelierToolTrace.Call("call_1", "read_file", null, "contenu", false)))))
                        .toJson())
                .build());
        agentProvider.enqueueFinal("Compris.");

        service.chat(userId, workspaceId, "et maintenant ?");

        assertThat(agentProvider.lastRequest.messages())
                .flatExtracting(AgentMessage::content)
                .noneMatch(block -> block instanceof AgentContentBlock.Reasoning
                        || block instanceof AgentContentBlock.RedactedReasoning);
    }
}
