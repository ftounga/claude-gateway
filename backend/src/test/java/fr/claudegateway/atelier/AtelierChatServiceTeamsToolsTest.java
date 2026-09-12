package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner;
import fr.claudegateway.teams.TeamsAccessService;
import fr.claudegateway.teams.TeamsToolCatalog;

/**
 * <b>Le volet Teams entre et sort de la panoplie de l'agent</b> (F-89 / SF-89-01).
 *
 * <p>{@code TeamsToolCatalogTest} tient la <b>règle</b> ; celui-ci tient le <b>branchement</b> :
 * que {@code buildTools} consulte réellement le catalogue, et que rien d'autre dans la panoplie ne
 * bouge — la panoplie d'un terminal de projet doit être, à l'outil près, celle d'avant F-89.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceTeamsToolsTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private AiAgentProvider agentProvider;
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

    private AtelierChatService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AtelierChatService(workspaceService, messageRepository, agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient,
                        new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, true),
                AtelierCheckpointRunner.none(), ProjectRulesSource.NONE,
                new TeamsToolCatalog(teamsAccess), null);
    }

    private static Workspace terminal(boolean teams) {
        Workspace workspace = new Workspace();
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        workspace.setTeamsTerminal(teams);
        return workspace;
    }

    private java.util.List<String> toolNames(Workspace workspace) {
        return service.buildTools(userId, workspace).stream().map(AgentTool::name).toList();
    }

    @Test
    @DisplayName("terminal Teams + droit : les outils teams_* s'ajoutent à la panoplie habituelle")
    void teamsToolsJoinTheBelt() {
        when(teamsAccess.hasAccess(userId)).thenReturn(true);

        assertThat(toolNames(terminal(true)))
                .contains("bash", "set_plan", TeamsToolCatalog.STATUS);
    }

    @Test
    @DisplayName("sans le droit : la panoplie est EXACTEMENT celle d'avant F-89")
    void withoutTheRightTheBeltIsUnchanged() {
        when(teamsAccess.hasAccess(userId)).thenReturn(false);

        assertThat(toolNames(terminal(true)))
                .containsExactly("read_file", "write_file", "edit_file", "bash", "explore", "set_plan");
    }

    @Test
    @DisplayName("UN TERMINAL DE PROJET N'A JAMAIS D'OUTIL TEAMS — même quand l'option est payée")
    void aProjectTerminalNeverReceivesTeamsTools() {
        when(teamsAccess.hasAccess(userId)).thenReturn(true);

        assertThat(toolNames(terminal(false)))
                .noneMatch(name -> name.startsWith(TeamsToolCatalog.PREFIX))
                .containsExactly("read_file", "write_file", "edit_file", "bash", "explore", "set_plan");
    }
}
