package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AgentTurnMode;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * Mode explicite « Réponse/Plan » vs « Agir » de la boucle maison (F-120 / SF-120-02).
 *
 * <p>Deux garanties non négociables : en {@link AgentTurnMode#ANSWER_PLAN}, <b>aucun</b> outil mutant
 * n'est déclaré au modèle (retiré à la source, pas seulement interdit par le prompt) ; en
 * {@link AgentTurnMode#ACT} — et quand le mode est absent — la panoplie et la consigne sont
 * <b>identiques</b> à celles d'avant SF-120-02 (rétrocompatibilité stricte).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceModeTest {

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

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

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

        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        lenient().when(quotaService.currentUsage(userId)).thenReturn(
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
    }

    private static Workspace bareWorkspace(WorkspaceExecutionTarget target) {
        Workspace workspace = new Workspace();
        workspace.setExecutionTarget(target);
        return workspace;
    }

    private static List<String> names(List<AgentTool> tools) {
        return tools.stream().map(AgentTool::name).toList();
    }

    // ------------------------------------------------------------ buildTools : la panoplie par mode

    @Test
    void actModeKeepsTheFullToolBeltOnRunner() {
        // Non-régression stricte : en ACT, la panoplie RUNNER est celle d'avant SF-120-02.
        Workspace runner = bareWorkspace(WorkspaceExecutionTarget.RUNNER);
        assertThat(names(service.buildTools(userId, runner, AgentTurnMode.ACT)))
                .containsExactly("read_file", "write_file", "edit_file", "grep", "glob", "bash",
                        "explore", "set_plan");
    }

    @Test
    void answerPlanRemovesMutatingToolsOnRunner() {
        // ANSWER_PLAN : ne restent que lecture, exploration et set_plan — pas write/edit/bash.
        Workspace runner = bareWorkspace(WorkspaceExecutionTarget.RUNNER);
        List<String> tools = names(service.buildTools(userId, runner, AgentTurnMode.ANSWER_PLAN));
        // grep/glob (F-121 / SF-121-01) sont de la lecture : ils survivent au mode Réponse/Plan.
        assertThat(tools).containsExactly("read_file", "grep", "glob", "explore", "set_plan");
        assertThat(tools).doesNotContain("write_file", "edit_file", "bash");
    }

    @Test
    void actModeKeepsTheFullToolBeltOnSandbox() {
        Workspace sandbox = bareWorkspace(WorkspaceExecutionTarget.SANDBOX);
        assertThat(names(service.buildTools(userId, sandbox, AgentTurnMode.ACT)))
                .containsExactly("list_files", "read_file", "write_file", "edit_file", "search_files",
                        "grep", "glob", "explore", "set_plan");
    }

    @Test
    void answerPlanRemovesMutatingToolsOnSandbox() {
        Workspace sandbox = bareWorkspace(WorkspaceExecutionTarget.SANDBOX);
        List<String> tools = names(service.buildTools(userId, sandbox, AgentTurnMode.ANSWER_PLAN));
        // La lecture/exploration de SANDBOX (list_files, search_files) survit ; write/edit non.
        assertThat(tools).containsExactly("list_files", "read_file", "search_files", "grep", "glob",
                "explore", "set_plan");
        assertThat(tools).doesNotContain("write_file", "edit_file");
    }

    @Test
    void anAbsentModeBuildsTheSamePanoplyAsAct() {
        // La surcharge historique buildTools(userId, workspace) vaut ACT (rétrocompatibilité).
        Workspace runner = bareWorkspace(WorkspaceExecutionTarget.RUNNER);
        assertThat(names(service.buildTools(userId, runner)))
                .isEqualTo(names(service.buildTools(userId, runner, AgentTurnMode.ACT)));
    }

    // ------------------------------------------------------------ buildSystemPrompt : la consigne de mode

    @Test
    void answerPlanAddsTheModeDirectiveToTheSystemPrompt() {
        Workspace sandbox = bareWorkspace(WorkspaceExecutionTarget.SANDBOX);
        sandbox.setId(workspaceId);
        sandbox.setUserId(userId);
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String answerPlan = service.buildSystemPrompt(userId, sandbox, AgentTurnMode.ANSWER_PLAN);
        String act = service.buildSystemPrompt(userId, sandbox, AgentTurnMode.ACT);

        assertThat(answerPlan).contains("Mode Réponse/Plan");
        assertThat(answerPlan).contains("tu n'exécutes rien");
        // ACT ne porte PAS la consigne de mode.
        assertThat(act).doesNotContain("Mode Réponse/Plan");
        // Dans les deux modes, la doctrine SF-120-01 et la discipline SF-119-02 restent présentes.
        assertThat(answerPlan).contains("Répondre d'abord, agir sur demande");
        assertThat(answerPlan).contains("Vérifie avant d'affirmer");
        assertThat(act).contains("Répondre d'abord, agir sur demande");
        assertThat(act).contains("Vérifie avant d'affirmer");
    }

    // ------------------------------------------------------------ bout en bout : le mode atteint le provider

    @Test
    void answerPlanReachesTheProviderWithNoMutatingToolDeclared() {
        stubSandbox();
        agentProvider.enqueueFinal("Voici mon plan.");

        service.chat(userId, workspaceId, "que ferais-tu ?", AgentTurnMode.ANSWER_PLAN);

        assertThat(agentProvider.lastRequest.mode()).isEqualTo(AgentTurnMode.ANSWER_PLAN);
        assertThat(agentProvider.toolBelts.get(0))
                .doesNotContain("write_file", "edit_file", "bash")
                .contains("read_file", "set_plan");
        assertThat(agentProvider.lastRequest.system()).contains("Mode Réponse/Plan");
    }

    @Test
    void actReachesTheProviderWithTheFullToolBelt() {
        stubSandbox();
        agentProvider.enqueueFinal("Fait.");

        service.chat(userId, workspaceId, "corrige le bug", AgentTurnMode.ACT);

        assertThat(agentProvider.lastRequest.mode()).isEqualTo(AgentTurnMode.ACT);
        assertThat(agentProvider.toolBelts.get(0)).contains("write_file", "edit_file");
        assertThat(agentProvider.lastRequest.system()).doesNotContain("Mode Réponse/Plan");
    }

    @Test
    void anAbsentModeIsTreatedAsAct() {
        stubSandbox();
        agentProvider.enqueueFinal("Fait.");

        // La surcharge historique chat(userId, workspaceId, message) vaut ACT.
        service.chat(userId, workspaceId, "bonjour");

        assertThat(agentProvider.lastRequest.mode()).isEqualTo(AgentTurnMode.ACT);
        assertThat(agentProvider.toolBelts.get(0)).contains("write_file", "edit_file");
    }

    /** Projet hébergé (SANDBOX) prêt pour un tour trivial : ni CLAUDE.md ni arbre. */
    private void stubSandbox() {
        Workspace sandbox = bareWorkspace(WorkspaceExecutionTarget.SANDBOX);
        sandbox.setId(workspaceId);
        sandbox.setUserId(userId);
        sandbox.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(sandbox);
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));
    }
}
