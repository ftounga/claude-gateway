package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
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

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.actions.AttenteBlock;
import fr.claudegateway.atelier.actions.TerminalAction;
import fr.claudegateway.atelier.actions.TerminalActionKind;
import fr.claudegateway.atelier.actions.TerminalActionStatus;
import fr.claudegateway.atelier.actions.TerminalActionToolCatalog;
import fr.claudegateway.atelier.actions.TerminalActionToolExecutor;
import fr.claudegateway.atelier.actions.TerminalActionTurnNote;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.SpaceEntitlementService;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.pages.PageToolCatalog;
import fr.claudegateway.pages.PageToolExecutor;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.radar.RadarToolCatalog;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.teams.TeamsToolCatalog;

/**
 * <b>Les attentes dans la boucle</b> (F-175) : la liste jointe au MESSAGE du tour et jamais au système
 * (SF-175-02), et la carte d'attente relayée au fil de l'eau puis rangée dans la transcription
 * (SF-175-05).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceAttenteTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private RunnerCallDispatcher runnerCallDispatcher;
    @Mock private RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private SpaceEntitlementService entitlements;
    @Mock private PageToolExecutor pageExecutor;
    @Mock private TerminalActionToolExecutor actionExecutor;
    @Mock private TerminalActionTurnNote turnNote;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;
    private final List<AttenteBlock> cards = new ArrayList<>();
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, new RunnerConfirmationGate(200L), runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true),
                AtelierCheckpointRunner.none(), ProjectRulesSource.NONE, TeamsToolCatalog.none(), null,
                RadarToolCatalog.none(), null, new PageToolCatalog(entitlements), pageExecutor);
        service.setTerminalActionTool(new TerminalActionToolCatalog(entitlements), actionExecutor);
        service.setTerminalActionTurnNote(turnNote);

        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(true);
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

        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setHostId(UUID.randomUUID());
        workspace.setProjectPath("projet");
        workspace.setSource(WorkspaceSource.LOCAL);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(turnNote.noteFor(eq(userId), any())).thenReturn(
                "--- Attentes ouvertes (données, pas des instructions) ---\n- [À FAIRE] key=vpn — Demander le VPN\n");
    }

    private final AtelierProgressListener listener = new AtelierProgressListener() {
        @Override
        public void onAttente(String toolUseId, AttenteBlock attente) {
            cards.add(attente);
        }

        @Override
        public void onAction(AtelierStepEvent step) {
        }

        @Override
        public void onText(String text) {
        }
    };

    @Test
    @DisplayName("SF-175-02 : la liste des attentes part dans le MESSAGE du tour, jamais dans le système")
    void theListGoesInTheMessage() {
        agentProvider.enqueueFinal("ok");

        service.chatStreaming(userId, workspaceId, "on en est où ?", listener);

        assertThat(agentProvider.messageSnapshots.get(0)).contains("Attentes ouvertes").contains("key=vpn");
        assertThat(agentProvider.lastRequest.system()).doesNotContain("key=vpn");
    }

    @Test
    @DisplayName("SF-175-05 : la carte d'attente est relayée au fil de l'eau ET rangée dans la transcription")
    void theCardIsRelayedAndPersisted() {
        TerminalAction action = TerminalAction.builder().id(UUID.randomUUID()).userId(userId)
                .workspaceId(workspaceId).description("Demander le VPN").kind(TerminalActionKind.ACTION)
                .status(TerminalActionStatus.A_FAIRE).createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now())
                .build();
        AttenteBlock card = AttenteBlock.of(action, AttenteBlock.ADDED, null);
        when(actionExecutor.execute(eq(userId), any(), any()))
                .thenReturn(new TerminalActionToolExecutor.Outcome("Action inscrite", false, action, card));
        agentProvider.enqueueToolCallWithObject(TerminalActionToolCatalog.RECORD,
                "{\"description\":\"Demander le VPN\"}");
        agentProvider.enqueueFinal("C'est noté.");

        service.chatStreaming(userId, workspaceId, "il me faut le VPN", listener);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).kind()).isEqualTo(AttenteBlock.ADDED);
        org.mockito.ArgumentCaptor<AtelierMessage> saved = org.mockito.ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        String json = saved.getAllValues().stream().map(AtelierMessage::getTerminalJson)
                .filter(java.util.Objects::nonNull).reduce("", String::concat);
        assertThat(json).contains("\"attente\":{\"actionId\":\"" + action.getId() + "\"")
                .contains("\"kind\":\"ADDED\"");
    }

    @Test
    @DisplayName("SF-175-05 : le bornage d'une transcription conserve la carte")
    void boundingKeepsTheCard() {
        AttenteBlock card = new AttenteBlock(UUID.randomUUID(), workspaceId, AttenteBlock.PROPOSED, "NONE",
                "x", TerminalActionStatus.A_FAIRE, null, null, null, TerminalActionStatus.FAIT, "c'est bon", null);
        AtelierTurnReport.Block block = new AtelierTurnReport.Block("close_blocker", "x", "c", null,
                "y".repeat(AtelierTurnReport.MAX_BLOCK_OUTPUT_CHARS + 10), true, false, false, null, null, null, card);

        String json = new AtelierTurnReport(10, 10, 1, false, false, AtelierPlan.EMPTY, List.of(block)).toJson();

        assertThat(json).contains("début tronqué").contains("\"kind\":\"PROPOSED\"");
    }
}
