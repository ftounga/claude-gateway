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
    @Mock private fr.claudegateway.runner.host.HostSpaceService spaces;

    private AtelierChatService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

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
                new TeamsToolCatalog(teamsAccess, spaces), null);
        // F-106 / SF-106-07 : par défaut le client est dans la Vigie ; les tests du retrait le nient.
        when(spaces.isActive(userId, hostId, fr.claudegateway.runner.host.ClientSpace.VIGIE))
                .thenReturn(true);
    }

    private Workspace terminal(boolean teams) {
        Workspace workspace = new Workspace();
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        workspace.setTeamsTerminal(teams);
        if (teams) {
            workspace.setHostId(hostId);
        }
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
                .containsExactly("read_file", "write_file", "edit_file", "grep", "glob", "bash", "explore", "set_plan");
    }

    @Test
    @DisplayName("UN TERMINAL DE PROJET N'A JAMAIS D'OUTIL TEAMS — même quand l'option est payée")
    void aProjectTerminalNeverReceivesTeamsTools() {
        when(teamsAccess.hasAccess(userId)).thenReturn(true);

        assertThat(toolNames(terminal(false)))
                .noneMatch(name -> name.startsWith(TeamsToolCatalog.PREFIX))
                .containsExactly("read_file", "write_file", "edit_file", "grep", "glob", "bash", "explore", "set_plan");
    }

    // ------------------------------------------------- F-89 / SF-89-04 : un terminal sans droit le dit

    @Test
    @DisplayName("SF-89-04 — terminal Teams sans droit : la consigne dit que le volet n'est pas actif")
    void withoutTheRightTheSystemPromptSaysSo() {
        when(teamsAccess.hasAccess(userId)).thenReturn(false);

        assertThat(service.buildSystemPrompt(userId, terminal(true)))
                .contains(TeamsToolCatalog.CLOSED_NOTICE);
    }

    @Test
    @DisplayName("SF-89-04 — terminal Teams avec droit : consigne sans l'avertissement")
    void withTheRightTheSystemPromptIsUnchanged() {
        when(teamsAccess.hasAccess(userId)).thenReturn(true);

        assertThat(service.buildSystemPrompt(userId, terminal(true)))
                .doesNotContain("Volet Teams non actif");
    }

    @Test
    @DisplayName("SF-89-04 — terminal de projet : jamais d'avertissement Teams")
    void aProjectTerminalNeverCarriesTheNotice() {
        when(teamsAccess.hasAccess(userId)).thenReturn(false);

        assertThat(service.buildSystemPrompt(userId, terminal(false)))
                .doesNotContain("Volet Teams non actif");
    }

    // ------------------------------------------- F-106 / SF-106-07 : le client quitte la Vigie

    @Test
    @DisplayName("SF-106-07 — client retiré de la Vigie : la panoplie est celle d'avant F-89")
    void whenTheClientLeftTheVigieTheBeltIsUnchanged() {
        when(teamsAccess.hasAccess(userId)).thenReturn(true);
        when(spaces.isActive(userId, hostId, fr.claudegateway.runner.host.ClientSpace.VIGIE))
                .thenReturn(false);

        assertThat(toolNames(terminal(true)))
                .containsExactly("read_file", "write_file", "edit_file", "grep", "glob", "bash", "explore", "set_plan");
    }

    @Test
    @DisplayName("SF-106-07 — client retiré de la Vigie : la consigne le dit, pas « volet non actif »")
    void whenTheClientLeftTheVigieTheSystemPromptSaysSo() {
        when(teamsAccess.hasAccess(userId)).thenReturn(true);
        when(spaces.isActive(userId, hostId, fr.claudegateway.runner.host.ClientSpace.VIGIE))
                .thenReturn(false);

        String prompt = service.buildSystemPrompt(userId, terminal(true));
        assertThat(prompt).contains(TeamsToolCatalog.REMOVED_FROM_VIGIE_NOTICE);
        assertThat(prompt).doesNotContain(TeamsToolCatalog.CLOSED_NOTICE);
    }

    @Test
    @DisplayName("SF-106-07 — client dans la Vigie : ni « retiré », ni « volet non actif »")
    void withTheClientInTheVigieNoNotice() {
        when(teamsAccess.hasAccess(userId)).thenReturn(true);

        String prompt = service.buildSystemPrompt(userId, terminal(true));
        assertThat(prompt).doesNotContain("retiré de la Vigie");
        assertThat(prompt).doesNotContain("Volet Teams non actif");
    }

    // ------------------------------------------------------------------ F-91 : le journal d'audit

    @Test
    @DisplayName("F-91 — la ligne d'audit d'un enregistrement dit L'USAGE et la confirmation")
    void theAuditLineOfACaptureSaysThePurposeAndTheConfirmation() {
        String target = auditTargetOf(TeamsToolCatalog.CAPTURE_START,
                "{\"purpose\":\"meeting\",\"participants_informed\":true}");

        // Le filigrane est dans l'image, la mention en tête du compte rendu — et CECI est le
        // troisième endroit où la trace voyage.
        assertThat(target)
                .contains("enregistrement local")
                .contains("usage=meeting")
                .contains("participants_prevenus=declare");
    }

    @Test
    @DisplayName("F-91 — une confirmation absente est tracée comme absente, jamais comme donnée")
    void anAbsentConfirmationIsTracedAsAbsent() {
        assertThat(auditTargetOf(TeamsToolCatalog.CAPTURE_START, "{\"purpose\":\"self\"}"))
                .contains("usage=self")
                .contains("participants_prevenus=non_declare");
    }

    @Test
    @DisplayName("F-91 — la ligne d'audit ne porte AUCUN contenu, comme toutes les lignes Teams")
    void theAuditLineCarriesNoContent() {
        String target = auditTargetOf(TeamsToolCatalog.CAPTURE_START,
                "{\"purpose\":\"meeting\",\"participants_informed\":true,"
                        + "\"subject\":\"Comité de pilotage ACME — budget 2027\"}");

        assertThat(target).doesNotContain("ACME").doesNotContain("budget");
    }

    @Test
    @DisplayName("F-91 — l'arrêt trace la capture visée, sans réinventer une confirmation")
    void stoppingTracesTheCapture() {
        assertThat(auditTargetOf(TeamsToolCatalog.CAPTURE_STOP, "{\"capture_id\":\"a1b2c3\"}"))
                .contains("enregistrement local")
                .contains("capture=a1b2c3")
                .doesNotContain("participants_prevenus");
    }

    @Test
    @DisplayName("les outils de LECTURE gardent leur cible d'audit d'avant : rien n'a bougé")
    void readingToolsKeepTheirAuditTarget() {
        assertThat(auditTargetOf(TeamsToolCatalog.READ_CONVERSATION,
                "{\"conversation_id\":\"19:abc\"}")).isEqualTo("conversation_id=19:abc");
    }

    private String auditTargetOf(String tool, String input) {
        try {
            return service.auditTarget(new fr.claudegateway.agent.AgentToolCall("call-1", tool,
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(input)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
