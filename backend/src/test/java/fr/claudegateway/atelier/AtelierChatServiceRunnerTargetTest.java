package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.ai.ModelCatalog;
import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * Boucle tool-use en <b>cible d'exécution RUNNER</b> (F-38 / SF-38-05).
 *
 * <p>Trois pièges sont vérifiés ici parce qu'ils échouent <b>en silence</b> : un projet Git refusé à
 * tort par le garde-fou du mode Assistant, une consigne système lue dans un stockage objet vide, et
 * une recherche qui rapatrierait tout le projet fichier par fichier au lieu d'un seul appel.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceRunnerTargetTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private ModelCatalog modelCatalog;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private RunnerToolGateway runnerToolGateway;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService, modelCatalog,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway);

        when(modelCatalog.defaultModel()).thenReturn("claude-model");
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of());
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        // Consigne système : par défaut le runner ne renvoie rien d'exploitable.
        when(runnerToolGateway.listFiles(eq(workspaceId), anyString())).thenReturn(ok(""));
        when(runnerToolGateway.readFile(eq(workspaceId), anyString(), anyString()))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE));
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false);
    }

    private Workspace stubWorkspace(WorkspaceSource source, WorkspaceExecutionTarget target) {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(source);
        workspace.setExecutionTarget(target);
        if (source == WorkspaceSource.GIT) {
            workspace.setGitOwner("acme");
            workspace.setGitRepo("app");
            workspace.setGitBranch("main");
        }
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        return workspace;
    }

    @Test
    void readFileGoesToTheRunnerAndNeverToObjectStorage() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.readFile(eq(workspaceId), anyString(), eq("src/a.ts")))
                .thenReturn(ok("const x = 1;"));
        agentProvider.enqueueToolCall("read_file", "path", "src/a.ts");
        agentProvider.enqueueFinal("Lu.");

        AtelierChatResult result = service.chat(userId, workspaceId, "lis src/a.ts");

        assertThat(result.actions()).extracting(a -> a.type() + ":" + a.path()).contains("read:src/a.ts");
        verify(runnerToolGateway).readFile(eq(workspaceId), anyString(), eq("src/a.ts"));
        verify(workspaceService, never()).readFile(any(), any(), any());
        assertThat(toolResultText()).isEqualTo("const x = 1;");
    }

    @Test
    void aTruncatedContentIsAnnouncedToTheModel() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.readFile(eq(workspaceId), anyString(), eq("gros.txt")))
                .thenReturn(new RunnerCallResult(true, "début", true, null, 5L, null, null, null, "", false));
        agentProvider.enqueueToolCall("read_file", "path", "gros.txt");
        agentProvider.enqueueFinal("Lu.");

        service.chat(userId, workspaceId, "lis gros.txt");

        assertThat(toolResultText()).isEqualTo("début\n… (contenu tronqué)");
    }

    @Test
    void writeFileKeepsTheHistoricalWordingAndIgnoresTheRunnerContent() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.writeFile(eq(workspaceId), anyString(), eq("a.txt"), eq("hop")))
                .thenReturn(ok("peu importe"));
        agentProvider.enqueueToolCall("write_file", "path", "a.txt", "content", "hop");
        agentProvider.enqueueFinal("Écrit.");

        AtelierChatResult result = service.chat(userId, workspaceId, "écris a.txt");

        assertThat(result.actions()).extracting(a -> a.type() + ":" + a.path()).contains("write:a.txt");
        assertThat(toolResultText()).isEqualTo("Fichier écrit : a.txt");
        verify(workspaceService, never()).writeFile(any(), any(), any(), any());
    }

    @Test
    void searchIsASingleRunnerCallNotOneReadPerFile() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.searchFiles(eq(workspaceId), anyString(), eq("TODO")))
                .thenReturn(ok("src/a.ts:3: // TODO"));
        agentProvider.enqueueToolCall("search_files", "query", "TODO");
        agentProvider.enqueueFinal("Trouvé.");

        service.chat(userId, workspaceId, "cherche TODO");

        verify(runnerToolGateway, times(1)).searchFiles(eq(workspaceId), anyString(), eq("TODO"));
        verify(workspaceService, never()).tree(any(), any());
        assertThat(toolResultText()).isEqualTo("src/a.ts:3: // TODO");
    }

    @Test
    void aRunnerErrorIsReturnedToTheModelAsAToolError() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.readFile(eq(workspaceId), anyString(), eq("a.ts")))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE));
        agentProvider.enqueueToolCall("read_file", "path", "a.ts");
        agentProvider.enqueueFinal("Tant pis.");

        service.chat(userId, workspaceId, "lis a.ts");

        AgentContentBlock.ToolResult block = lastToolResult();
        assertThat(block.isError()).isTrue();
        assertThat(block.content()).contains("Aucun runner");
    }

    @Test
    void theSystemPromptIsReadThroughTheRunner() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.listFiles(eq(workspaceId), anyString()))
                .thenReturn(ok(".claude/skills/deploy.md\nsrc/a.ts"));
        when(runnerToolGateway.readFile(eq(workspaceId), anyString(), eq("CLAUDE.md")))
                .thenReturn(ok("# Conventions maison"));
        when(runnerToolGateway.readFile(eq(workspaceId), anyString(), eq(".claude/skills/deploy.md")))
                .thenReturn(ok("Skill de déploiement"));
        agentProvider.enqueueFinal("Bonjour.");

        service.chat(userId, workspaceId, "salut");

        assertThat(agentProvider.lastRequest.system()).contains("# Conventions maison");
        assertThat(agentProvider.lastRequest.system()).contains("Skill de déploiement");
        verify(workspaceService, never()).tree(any(), any());
    }

    @Test
    void aGitProjectIsNoLongerRefusedWhenItRunsOnTheMachine() {
        // Le dépôt est cloné sur la machine de l'utilisateur : le garde-fou « mode Terminal
        // obligatoire » (F-31) serait ici un faux positif.
        stubWorkspace(WorkspaceSource.GIT, WorkspaceExecutionTarget.RUNNER);
        agentProvider.enqueueFinal("Bonjour.");

        AtelierChatResult result = service.chat(userId, workspaceId, "salut");

        assertThat(result.reply()).isEqualTo("Bonjour.");
    }

    @Test
    void aGitProjectInSandboxTargetIsStillRefused() {
        // Non-régression F-31 / SF-31-03 : sans runner, le stockage objet est bien vide.
        stubWorkspace(WorkspaceSource.GIT, WorkspaceExecutionTarget.SANDBOX);
        agentProvider.enqueueFinal("Bonjour.");

        assertThatThrownBy(() -> service.chat(userId, workspaceId, "salut"))
                .isInstanceOf(fr.claudegateway.atelier.git.GitWorkspaceModeException.class);
    }

    @Test
    void aToolCallWithoutProviderIdStillCorrelates() {
        stubWorkspace(WorkspaceSource.ARCHIVE, WorkspaceExecutionTarget.RUNNER);
        when(runnerToolGateway.listFiles(eq(workspaceId), anyString())).thenReturn(ok("a.ts"));
        agentProvider.enqueueToolCallWithoutId("list_files");
        agentProvider.enqueueFinal("Fini.");

        service.chat(userId, workspaceId, "liste");

        // Le bloc tool_result doit référencer un id non vide, sinon le fournisseur rejette le tour.
        assertThat(lastToolResult().toolUseId()).isNotBlank();
    }

    /** Texte du dernier {@code tool_result} transmis au modèle. */
    private String toolResultText() {
        return lastToolResult().content();
    }

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
}
