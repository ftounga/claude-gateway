package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
 * <b>L'outil {@code page_publish} dans la boucle</b> (F-109 / SF-109-02) : la garde dans {@code buildTools},
 * le guide dans la consigne, l'accord d'un clic, et le second verrou.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServicePageToolTest {

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
    @Mock private PageToolExecutor executor;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;
    private Listener listener;
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        listener = new Listener();
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, new RunnerConfirmationGate(200L), runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true),
                AtelierCheckpointRunner.none(), ProjectRulesSource.NONE, TeamsToolCatalog.none(), null,
                RadarToolCatalog.none(), null, new PageToolCatalog(entitlements), executor);

        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(true);
        when(entitlements.isEntitled(userId, EntitlementSpace.VIGIE)).thenReturn(true);
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
        when(executor.execute(eq(userId), any(), anyString(), any()))
                .thenReturn(new PageToolExecutor.Outcome("Page publiée : « Maquette » — version 1.", false, null));
    }

    private Workspace terminal(WorkspaceExecutionTarget target, boolean teams) {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setHostId(hostId);
        workspace.setProjectPath("projet");
        workspace.setSource(WorkspaceSource.LOCAL);
        workspace.setExecutionTarget(target);
        workspace.setTeamsTerminal(teams);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        return workspace;
    }

    private List<String> toolNames(Workspace workspace) {
        return service.buildTools(userId, workspace).stream().map(AgentTool::name).toList();
    }

    private void publishCall() {
        agentProvider.enqueueToolCallWithObject(PageToolCatalog.PUBLISH,
                "{\"title\":\"Maquette\",\"html\":\"<h1>Forge</h1>\"}");
        agentProvider.enqueueFinal("Voilà la page.");
    }

    @Test
    @DisplayName("CA1 — droit de l'espace : page_publish est donné sur un poste comme sur un projet hébergé")
    void givenWithTheRight() {
        assertThat(toolNames(terminal(WorkspaceExecutionTarget.RUNNER, false))).contains(PageToolCatalog.PUBLISH);
        assertThat(toolNames(terminal(WorkspaceExecutionTarget.RUNNER, true))).contains(PageToolCatalog.PUBLISH);
        // SF-109-06 : le projet hébergé (SANDBOX) est désormais ouvert lui aussi, avec le guide dans la consigne.
        assertThat(toolNames(terminal(WorkspaceExecutionTarget.SANDBOX, false))).contains(PageToolCatalog.PUBLISH);
        assertThat(service.buildSystemPrompt(userId, terminal(WorkspaceExecutionTarget.SANDBOX, false)))
                .contains("--- Pages");
    }

    @Test
    @DisplayName("CA2 — sans le droit de l'espace : ni outil, ni guide, quelle que soit la cible")
    void closedWithoutRight() {
        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(false);
        assertThat(toolNames(terminal(WorkspaceExecutionTarget.RUNNER, false))).doesNotContain(PageToolCatalog.PUBLISH);
        assertThat(service.buildSystemPrompt(userId, terminal(WorkspaceExecutionTarget.RUNNER, false)))
                .doesNotContain("--- Pages");
        assertThat(toolNames(terminal(WorkspaceExecutionTarget.SANDBOX, false))).doesNotContain(PageToolCatalog.PUBLISH);
        // Le terminal Teams lit le droit Vigie, pas Forge.
        assertThat(toolNames(terminal(WorkspaceExecutionTarget.RUNNER, true))).contains(PageToolCatalog.PUBLISH);
        when(entitlements.isEntitled(userId, EntitlementSpace.VIGIE)).thenReturn(false);
        assertThat(toolNames(terminal(WorkspaceExecutionTarget.RUNNER, true))).doesNotContain(PageToolCatalog.PUBLISH);
    }

    @Test
    @DisplayName("CA4 — garde ouverte : la consigne porte le guide de conception")
    void systemPromptCarriesTheGuide() {
        assertThat(service.buildSystemPrompt(userId, terminal(WorkspaceExecutionTarget.RUNNER, false)))
                .contains(PageToolCatalog.DESIGN_GUIDE);
    }

    @Test
    @DisplayName("CA5 — accord donné : l'exécuteur range la page, l'étape montre le titre")
    void allowedPublishRuns() {
        terminal(WorkspaceExecutionTarget.RUNNER, false);
        listener.decision = true;
        publishCall();

        service.chatStreaming(userId, workspaceId, "fais-moi une page", listener);

        assertThat(listener.requests).hasSize(1);
        assertThat(listener.requests.get(0).detail()).contains("Publier la page « Maquette »");
        verify(executor).execute(eq(userId), any(Workspace.class), anyString(), any());
        assertThat(listener.steps).anyMatch(step -> PageToolCatalog.PUBLISH.equals(step.type())
                && "Maquette".equals(step.path()));
    }

    @Test
    @DisplayName("CA8 — refus : rien n'est rangé")
    void deniedPublishDoesNothing() {
        terminal(WorkspaceExecutionTarget.RUNNER, false);
        listener.decision = false;
        publishCall();

        service.chatStreaming(userId, workspaceId, "fais-moi une page", listener);

        verify(executor, never()).execute(any(), any(), any(), any());
    }

    @Test
    @DisplayName("CA8 — « tout autoriser pour ce message » : une seule demande, les deux pages sont rangées")
    void blanketApprovalCoversThePage() {
        terminal(WorkspaceExecutionTarget.RUNNER, false);
        listener.decision = true;
        listener.allowAllOnFirst = true;
        agentProvider.enqueueToolCallWithObject(PageToolCatalog.PUBLISH, "{\"title\":\"Une\",\"html\":\"<p>1</p>\"}");
        agentProvider.enqueueToolCallWithObject(PageToolCatalog.PUBLISH, "{\"title\":\"Deux\",\"html\":\"<p>2</p>\"}");
        agentProvider.enqueueFinal("Deux pages.");

        service.chatStreaming(userId, workspaceId, "fais-moi deux pages", listener);

        assertThat(listener.requests).hasSize(1);
        verify(executor, times(2)).execute(eq(userId), any(Workspace.class), anyString(), any());
    }

    @Test
    @DisplayName("CA9 — SECOND VERROU : hors garde, un appel forcé est refusé sans demande ni rangement")
    void forcedCallOutsideTheGuardIsRefused() {
        terminal(WorkspaceExecutionTarget.RUNNER, false);
        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(false);
        listener.decision = true;
        publishCall();

        service.chatStreaming(userId, workspaceId, "fais-moi une page", listener);

        assertThat(listener.requests).isEmpty();
        verify(executor, never()).execute(any(), any(), any(), any());
    }

    // ------------------------------------------------------------ F-109 / SF-109-03 : le bloc de page

    private fr.claudegateway.pages.PageService.PublishedPage published() {
        fr.claudegateway.pages.Page page = fr.claudegateway.pages.Page.builder().id(UUID.randomUUID())
                .userId(userId).title("Maquette").description("La Forge refondue.").currentVersion(2).build();
        return new fr.claudegateway.pages.PageService.PublishedPage(page,
                fr.claudegateway.pages.PageVersion.builder().version(2).build());
    }

    @Test
    @DisplayName("SF-109-03 CA1 — publication réussie : événement page ET bloc de transcription")
    void successfulPublishEmitsThePageBlock() throws Exception {
        terminal(WorkspaceExecutionTarget.RUNNER, false);
        fr.claudegateway.pages.PageService.PublishedPage published = published();
        when(executor.execute(eq(userId), any(), anyString(), any()))
                .thenReturn(new PageToolExecutor.Outcome("Page publiée.", false, published));
        listener.decision = true;
        publishCall();

        service.chatStreaming(userId, workspaceId, "fais-moi une page", listener);

        assertThat(listener.pages).hasSize(1);
        assertThat(listener.pages.get(0).pageId()).isEqualTo(published.page().getId());
        assertThat(listener.pages.get(0).title()).isEqualTo("Maquette");
        assertThat(listener.pages.get(0).version()).isEqualTo(2);

        org.mockito.ArgumentCaptor<AtelierMessage> saved = org.mockito.ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        String json = saved.getAllValues().stream().map(AtelierMessage::getTerminalJson)
                .filter(java.util.Objects::nonNull).reduce("", String::concat);
        assertThat(json).contains("\"page\":{\"pageId\":\"" + published.page().getId() + "\"")
                .contains("\"title\":\"Maquette\"");
    }

    @Test
    @DisplayName("SF-109-03 CA1 — publication refusée : aucun bloc de page")
    void refusedPublishEmitsNothing() {
        terminal(WorkspaceExecutionTarget.RUNNER, false);
        listener.decision = false;
        publishCall();

        service.chatStreaming(userId, workspaceId, "fais-moi une page", listener);

        assertThat(listener.pages).isEmpty();
    }

    @Test
    @DisplayName("SF-109-03 CA2 — le bornage d'une transcription conserve la page")
    void boundingKeepsThePage() throws Exception {
        fr.claudegateway.pages.PageBlock page = new fr.claudegateway.pages.PageBlock(UUID.randomUUID(), "T", null, 1);
        AtelierTurnReport.Block block = new AtelierTurnReport.Block(PageToolCatalog.PUBLISH, "T", "c", null,
                "x".repeat(AtelierTurnReport.MAX_BLOCK_OUTPUT_CHARS + 10), true, false, false, null, null, page);

        String json = new AtelierTurnReport(10, 10, 1, false, false, AtelierPlan.EMPTY, List.of(block)).toJson();

        assertThat(json).contains("début tronqué").contains("\"pageId\":\"" + page.pageId() + "\"");
    }

    private final class Listener implements AtelierProgressListener {

        private final List<fr.claudegateway.pages.PageBlock> pages = new ArrayList<>();

        @Override
        public void onPage(String toolUseId, fr.claudegateway.pages.PageBlock page) {
            pages.add(page);
        }

        private final List<AtelierConfirmRequest> requests = new ArrayList<>();
        private final List<AtelierStepEvent> steps = new ArrayList<>();
        private Boolean decision;
        private boolean allowAllOnFirst;

        @Override
        public void onAction(AtelierStepEvent step) {
            steps.add(step);
        }

        @Override
        public void onText(String text) {
            // rien
        }

        @Override
        public void onConfirmRequest(AtelierConfirmRequest request) {
            boolean first = requests.isEmpty();
            requests.add(request);
            if (decision != null) {
                service.confirmToolUse(userId, workspaceId, request.toolUseId(), decision, null,
                        allowAllOnFirst && first);
            }
        }
    }
}
