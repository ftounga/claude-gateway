package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner;
import fr.claudegateway.atelier.quickaction.QuickActionDtos.QuickActionView;
import fr.claudegateway.atelier.quickaction.QuickActionService;
import fr.claudegateway.atelier.quickaction.QuickActionToolCatalog;
import fr.claudegateway.atelier.quickaction.QuickActionToolExecutor;
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
 * F-275 / SF-275-02 — l'outil {@code action_rapide} dans un vrai tour : déclaré au terminal du sujet, exécuté sous le
 * {@code user_id} du tour, absent du terminal Teams.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceQuickActionToolTest {

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
    @Mock private QuickActionService quickActions;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private Workspace terminal;

    private final AtelierProgressListener listener = new AtelierProgressListener() {
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
                runnerToolGateway, runnerCallDispatcher, new RunnerConfirmationGate(200L, 200L), runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true),
                AtelierCheckpointRunner.none(), ProjectRulesSource.NONE, TeamsToolCatalog.none(), null,
                RadarToolCatalog.none(), null, new PageToolCatalog(entitlements), pageExecutor);
        QuickActionToolCatalog catalog = new QuickActionToolCatalog();
        service.setQuickActionTools(catalog, new QuickActionToolExecutor(catalog, quickActions));

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

        terminal = new Workspace();
        terminal.setId(workspaceId);
        terminal.setUserId(userId);
        terminal.setName("reporting-securite");
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(terminal);
        when(quickActions.create(eq(userId), any(Workspace.class), any())).thenReturn(new QuickActionView("c:x", "PERSO",
                UUID.randomUUID(), "Point hebdo", "point hebdo", null, "SUJET", null, null, false, "DEPOSER", List.of(),
                null, null));
    }

    @Test
    void theToolIsDeclaredAndRunsUnderTheUserOfTheTurn() {
        agentProvider.enqueueToolCall(QuickActionToolCatalog.NAME, "operation", "ajouter", "libelle", "Point hebdo",
                "phrase", "point hebdo");
        agentProvider.enqueueFinal("C'est rangé.");

        service.chatStreaming(userId, workspaceId, "ajoute une action rapide point hebdo", listener);

        assertThat(agentProvider.toolNamesSeen).contains(QuickActionToolCatalog.NAME);
        verify(quickActions).create(eq(userId), eq(terminal), any());
    }

    @Test
    void theTeamsTerminalNeitherDeclaresNorRunsIt() {
        terminal.setTeamsTerminal(true);
        agentProvider.enqueueToolCall(QuickActionToolCatalog.NAME, "operation", "ajouter", "libelle", "a", "phrase", "b");
        agentProvider.enqueueFinal("Non.");

        service.chatStreaming(userId, workspaceId, "ajoute une action", listener);

        assertThat(agentProvider.toolNamesSeen).doesNotContain(QuickActionToolCatalog.NAME);
        verify(quickActions, never()).create(any(), any(Workspace.class), any());
    }
}
