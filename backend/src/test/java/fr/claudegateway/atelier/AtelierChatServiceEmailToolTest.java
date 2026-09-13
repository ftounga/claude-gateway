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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.mail.ClientMailReceipt;
import fr.claudegateway.mail.ClientMailTool;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.teams.TeamsToolCatalog;

/**
 * <b>{@code email_me} dans la boucle</b> (F-110 / SF-110-02) : l'outil est donné selon sa garde, l'appel part
 * dans la gateway (jamais sur la machine), et son reçu est relayé puis écrit dans la transcription.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceEmailToolTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private RunnerCallDispatcher runnerCallDispatcher;
    @Mock private RunnerConfirmationGate confirmationGate;
    @Mock private RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private ClientMailTool mailTool;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final List<ClientMailReceipt> relayed = new ArrayList<>();
    private final ClientMailReceipt receipt = new ClientMailReceipt(UUID.randomUUID().toString(),
            "franck@cagip.fr", true, "CAGIP", "Compte rendu", 0, "PENDING");

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true),
                AtelierCheckpointRunner.none(), ProjectRulesSource.NONE, TeamsToolCatalog.none(), null);
        service.setClientMailTool(mailTool);

        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId)).thenReturn(List.of());
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
    }

    private Workspace terminal() {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        workspace.setHostId(hostId);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        return workspace;
    }

    private AtelierProgressListener listener() {
        return new AtelierProgressListener() {
            @Override
            public void onAction(AtelierStepEvent step) {
                // rien
            }

            @Override
            public void onText(String text) {
                // rien
            }

            @Override
            public void onEmail(String toolUseId, ClientMailReceipt email) {
                relayed.add(email);
            }
        };
    }

    private List<AtelierTurnReport.Block> persistedBlocks() {
        org.mockito.ArgumentCaptor<AtelierMessage> captor = org.mockito.ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        AtelierMessage assistant = captor.getAllValues().stream()
                .filter(message -> "ASSISTANT".equals(message.getRole()))
                .reduce((first, second) -> second)
                .orElseThrow();
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<AtelierTurnReport.Block> blocks = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode block : mapper.readTree(assistant.getTerminalJson()).path("blocks")) {
                blocks.add(mapper.treeToValue(block, AtelierTurnReport.Block.class));
            }
            return blocks;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("la garde de ClientMailTool décide : outil donné quand elle l'ouvre, absent sinon")
    void theToolJoinsTheBeltOnlyWhenTheGuardOpens() {
        Workspace workspace = terminal();
        AgentTool tool = new AgentTool(ClientMailTool.NAME, "…", java.util.Map.of("type", "object"));
        when(mailTool.toolFor(userId, workspace)).thenReturn(Optional.of(tool));
        assertThat(service.buildTools(userId, workspace)).extracting(AgentTool::name).contains(ClientMailTool.NAME);

        when(mailTool.toolFor(userId, workspace)).thenReturn(Optional.empty());
        assertThat(service.buildTools(userId, workspace)).extracting(AgentTool::name).doesNotContain(ClientMailTool.NAME);

        service.setClientMailTool(null);
        assertThat(service.buildTools(userId, workspace)).extracting(AgentTool::name).doesNotContain(ClientMailTool.NAME);
    }

    @Test
    @DisplayName("un appel email_me part dans la gateway : reçu relayé, écrit dans la transcription, rien sur la machine")
    void aCallIsQueuedRelayedAndPersisted() {
        Workspace workspace = terminal();
        when(mailTool.send(eq(userId), eq(workspace), any()))
                .thenReturn(new ClientMailTool.Outcome("Courriel mis en file pour franck@cagip.fr", false, receipt));
        agentProvider.enqueueToolCallWithObject(ClientMailTool.NAME,
                "{\"subject\":\"Compte rendu\",\"body\":\"# CR\",\"to\":\"tiers@ailleurs.fr\"}");
        agentProvider.enqueueFinal("C'est parti dans ta boîte.");

        service.chatStreaming(userId, workspaceId, "envoie-moi le compte rendu", listener());

        assertThat(relayed).containsExactly(receipt);
        assertThat(persistedBlocks()).anySatisfy(block -> {
            assertThat(block.tool()).isEqualTo(ClientMailTool.NAME);
            assertThat(block.command()).isEqualTo("Courriel · Compte rendu").doesNotContain("tiers");
            assertThat(block.email()).isEqualTo(receipt);
        });
        org.mockito.Mockito.verifyNoInteractions(runnerToolGateway, runnerCallDispatcher, confirmationGate);
    }

    @Test
    @DisplayName("un refus de l'outil ne pose aucun reçu")
    void aRefusalPostsNoReceipt() {
        Workspace workspace = terminal();
        when(mailTool.send(eq(userId), eq(workspace), any()))
                .thenReturn(new ClientMailTool.Outcome("Courriel refusé : il contient manifestement un mot de passe.",
                        true, null));
        agentProvider.enqueueToolCallWithObject(ClientMailTool.NAME, "{\"subject\":\"x\",\"body\":\"password=abcdef1\"}");
        agentProvider.enqueueFinal("Je retire le secret.");

        service.chatStreaming(userId, workspaceId, "envoie-moi mes accès", listener());

        assertThat(relayed).isEmpty();
        assertThat(persistedBlocks()).allSatisfy(block -> assertThat(block.email()).isNull());
        assertThat(persistedBlocks()).anySatisfy(block -> assertThat(block.error()).isTrue());
    }
}
