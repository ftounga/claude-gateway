package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner;
import fr.claudegateway.atelier.storage.InMemoryWorkspaceStorage;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.teams.TeamsAccessService;
import fr.claudegateway.teams.TeamsToolCatalog;
import fr.claudegateway.teams.block.TeamsBlockCard;
import fr.claudegateway.teams.block.TeamsMomentImageService;
import fr.claudegateway.teams.block.TeamsReadFailure;

/**
 * <b>Quand Teams échoue, ça se voit, et le repli est un choix</b> (F-89 / SF-89-11).
 *
 * <p>Le constat de production : sur un zéro de lecture Teams, l'agent enchaînait {@code bash} et
 * répondait depuis le poste, en silence. Ces tests tiennent la décision : un bloc d'échec est posé, et
 * <b>aucun outil de fond n'est émis avant le choix de l'utilisateur</b> — sauf s'il a explicitement
 * autorisé le repli, auquel cas un bandeau marque la réponse.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceTeamsReadFailureTest {

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
    @Mock private TeamsAccessService teamsAccess;
    @Mock private fr.claudegateway.runner.host.HostSpaceService spaces;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final RunnerTarget runnerTarget = new RunnerTarget(hostId, workspaceId, "projet");

    /** Ce que l'écran a vu passer au fil de l'eau. */
    private final List<TeamsBlockCard> relayed = new ArrayList<>();

    // La phrase que le runner écrit sur un « rien servi » — reconnue telle quelle par la gateway.
    private static final String NOTHING_SERVED =
            "Aucune conversation trouvée. Teams n'a rien servi d'utile depuis le rattachement "
                    + "(0 réponse) : ouvrez l'écran voulu dans Teams, puis redemandez.";

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        TeamsMomentImageService momentImages =
                new TeamsMomentImageService(new InMemoryWorkspaceStorage());
        service = new AtelierChatService(workspaceService, messageRepository,
                (AiAgentProvider) agentProvider, byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient,
                        new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, true),
                AtelierCheckpointRunner.none(), ProjectRulesSource.NONE,
                new TeamsToolCatalog(teamsAccess, spaces), momentImages);

        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(java.util.Optional.empty());
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
        when(teamsAccess.hasAccess(userId)).thenReturn(true);
        when(spaces.isActive(userId, hostId, fr.claudegateway.runner.host.ClientSpace.VIGIE))
                .thenReturn(true);
        when(runnerToolGateway.teamsRead(eq(runnerTarget), anyString(),
                eq(TeamsToolCatalog.FIND_CONVERSATIONS), any()))
                .thenReturn(new RunnerCallResult(true, NOTHING_SERVED, false, null, 5L, null, null,
                        null, "", false));
        when(runnerToolGateway.bash(eq(runnerTarget), anyString(), anyString(), any(), anyLong(),
                any())).thenReturn(new RunnerCallResult(true, "des fichiers du projet…", false, 0,
                        12L, null, null, null, "ok\n", false));
    }

    private Workspace teamsRunnerTerminal() {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setHostId(hostId);
        workspace.setProjectPath("projet");
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        workspace.setAgentAskBeforeBash(false);
        workspace.setTeamsTerminal(true);
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
            public void onCard(String toolUseId, TeamsBlockCard card) {
                relayed.add(card);
            }
        };
    }

    /** Le dernier tool_result transmis au modèle — celui du dernier outil appelé avant la fin. */
    private AgentContentBlock.ToolResult lastToolResult() {
        AgentContentBlock.ToolResult found = null;
        for (AgentMessage message : agentProvider.lastRequest.messages()) {
            for (AgentContentBlock block : message.content()) {
                if (block instanceof AgentContentBlock.ToolResult result) {
                    found = result;
                }
            }
        }
        assertThat(found).as("aucun tool_result transmis au modèle").isNotNull();
        return found;
    }

    @Test
    @DisplayName("un zéro de lecture Teams pose le bloc READ_FAILED et n'émet AUCUN bash de fond avant le choix")
    void aZeroReadPostsTheFailureBlockAndBlocksTheFallbackBash() {
        teamsRunnerTerminal();
        // L'agent lit Teams (zéro), puis, sans y être autorisé, tente de répondre depuis le projet.
        agentProvider.enqueueToolCall(TeamsToolCatalog.FIND_CONVERSATIONS, "query", "migration");
        agentProvider.enqueueToolCall("bash", "command", "grep -rn migration .");
        agentProvider.enqueueFinal("Voilà ce que j'ai trouvé dans le projet.");

        service.chatStreaming(userId, workspaceId, "qu'est-ce qu'on attend de moi sur Teams ?",
                listener());

        // (1) Un bloc d'échec coloré est posé, avec le motif exact.
        assertThat(relayed).hasSize(1);
        assertThat(relayed.get(0).kind()).isEqualTo(TeamsBlockCard.Kind.READ_FAILED);
        assertThat(relayed.get(0).reason()).isEqualTo("NOTHING_SERVED");
        // (3) Aucun bash de repli n'a atteint la machine avant le choix de l'utilisateur.
        verify(runnerToolGateway, never()).bash(any(), anyString(), anyString(), any(), anyLong(),
                any());
        // Et le modèle a reçu le refus, pas le contenu du projet.
        assertThat(lastToolResult().isError()).isTrue();
        assertThat(lastToolResult().content()).isEqualTo(TeamsReadFailure.GATE_MESSAGE);
    }

    @Test
    @DisplayName("« Chercher dans le projet » autorise le repli : le bash passe, et un bandeau marque la réponse")
    void authorizingFallbackLetsTheBashThroughAndMarksTheAnswer() {
        teamsRunnerTerminal();
        agentProvider.enqueueToolCall(TeamsToolCatalog.FIND_CONVERSATIONS, "query", "migration");
        agentProvider.enqueueToolCall("bash", "command", "grep -rn migration .");
        agentProvider.enqueueFinal("Réponse basée sur le projet.");

        service.chatStreaming(userId, workspaceId, TeamsReadFailure.FALLBACK_PRECISION, listener());

        // Le bandeau « réponse basée sur le projet » ouvre le tour.
        assertThat(relayed).anySatisfy(card ->
                assertThat(card.kind()).isEqualTo(TeamsBlockCard.Kind.PROJECT_FALLBACK));
        // Le repli étant autorisé, le bash atteint bien la machine.
        verify(runnerToolGateway).bash(eq(runnerTarget), anyString(), eq("grep -rn migration ."),
                any(), anyLong(), any());
    }

    @Test
    @DisplayName("« Réessayer » n'autorise pas le repli : le bash reste bloqué, aucun bandeau")
    void retryingDoesNotAuthorizeFallback() {
        teamsRunnerTerminal();
        agentProvider.enqueueToolCall(TeamsToolCatalog.FIND_CONVERSATIONS, "query", "migration");
        agentProvider.enqueueToolCall("bash", "command", "grep -rn migration .");
        agentProvider.enqueueFinal("…");

        service.chatStreaming(userId, workspaceId, TeamsReadFailure.RETRY_PRECISION, listener());

        assertThat(relayed).noneSatisfy(card ->
                assertThat(card.kind()).isEqualTo(TeamsBlockCard.Kind.PROJECT_FALLBACK));
        verify(runnerToolGateway, never()).bash(any(), anyString(), anyString(), any(), anyLong(),
                any());
    }

    @Test
    @DisplayName("terminal Teams avec droit : la consigne système porte la règle non négociable d'échec")
    void theSystemPromptCarriesTheNonNegotiableRule() {
        Workspace workspace = teamsRunnerTerminal();
        assertThat(service.buildSystemPrompt(userId, workspace))
                .contains(TeamsToolCatalog.READ_FAILURE_RULE);
    }
}
