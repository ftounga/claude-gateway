package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * F-150 / SF-150-04 — politique de modèle de la sous-boucle `task` : elle tourne sur
 * {@code app.atelier.task-model} quand il est réglé, sinon **repli** sur le modèle principal. Le
 * modèle voyage comme une chaîne via {@link AiAgentProvider} (Provider Independence).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceTaskModelTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    @Mock private fr.claudegateway.runner.exec.RunnerConfirmationGate confirmationGate;
    @Mock private fr.claudegateway.runner.audit.RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private ModelRecordingProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ModelRecordingProvider();
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setHostId(hostId);
        workspace.setProjectPath("projet");
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
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
        when(runnerToolGateway.worktreeCreate(any(), anyString(), anyString())).thenReturn(
                new RunnerCallResult(true,
                        "{\"worktreePath\":\".atelier-worktrees/wt1\",\"branch\":\"atelier/task/wt1\"}",
                        false, null, 5L, null, null, null, "", false));
        when(runnerToolGateway.worktreeRemove(any(), anyString(), anyString())).thenReturn(
                new RunnerCallResult(true, "", false, null, 5L, null, null, null, "", false));
    }

    private AtelierChatService serviceWith(AtelierProperties properties) {
        return new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) provider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                runnerHostService, properties);
    }

    /** 25 composants, avec le modèle `task` réglé (25ᵉ) ; storageExecution=true (13ᵉ). */
    private static AtelierProperties withTaskModel(String taskModel) {
        return new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, true, null, null, null, null, null, null, null, null, null, null,
                null, taskModel);
    }

    /** Forme de compatibilité (13 composants) : taskModel absent (null) ; storageExecution=true. */
    private static AtelierProperties withoutTaskModel() {
        return new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, true);
    }

    @Test
    void theTaskSubLoopRunsOnTheConfiguredModelWhileTheMainLoopKeepsOpus() {
        AtelierChatService service = serviceWith(withTaskModel("claude-sonnet-5"));

        service.chat(userId, workspaceId, "délègue une tâche");

        assertThat(provider.subLoopModel).isEqualTo("claude-sonnet-5");
        assertThat(provider.mainModel).isEqualTo(AtelierProperties.DEFAULT_MODEL);
        assertThat(provider.mainModel).isEqualTo("claude-opus-5");
    }

    @Test
    void withoutConfigurationTheTaskSubLoopFallsBackToTheMainModel() {
        AtelierChatService service = serviceWith(withoutTaskModel());

        service.chat(userId, workspaceId, "délègue une tâche");

        assertThat(provider.subLoopModel).isEqualTo("claude-opus-5");
        assertThat(provider.subLoopModel).isEqualTo(provider.mainModel);
    }

    /**
     * Provider scripté : la boucle principale émet un {@code task} au premier tour puis conclut ; la
     * sous-boucle `task` (consigne « Tu es un sous-agent… ») conclut d'emblée. Chaque appel enregistre
     * le modèle reçu — c'est {@code AgentTurnRequest.model()} qui prouve quel modèle a servi.
     */
    private static final class ModelRecordingProvider implements AiAgentProvider {

        private final ObjectMapper mapper = new ObjectMapper();
        private int mainCalls = 0;
        volatile String mainModel;
        volatile String subLoopModel;

        @Override
        public AgentTurn nextTurn(AgentTurnRequest request) {
            String system = request.system();
            boolean subLoop = system != null && system.startsWith("Tu es un sous-agent");
            if (subLoop) {
                subLoopModel = request.model();
                return new AgentTurn("Sous-tâche accomplie.", List.of(), true, 5, 5);
            }
            mainModel = request.model();
            if (mainCalls++ == 0) {
                ObjectNode input = mapper.createObjectNode();
                input.put("prompt", "écris a.txt");
                List<AgentToolCall> calls = new ArrayList<>();
                calls.add(new AgentToolCall("task-0", "task", input));
                return new AgentTurn("", calls, false, 5, 5);
            }
            return new AgentTurn("done", List.of(), true, 5, 5);
        }
    }
}
