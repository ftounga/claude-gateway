package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * F-195 / SF-195-03 — les lectures groupées d'une même réponse partent ENSEMBLE ; tout ce qui écrit
 * reste séquentiel et sert de barrière ; un vieux runner (une commande à la fois) n'en reçoit qu'une.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceParallelToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long COMMAND_MS = 300L;

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    @Mock private RunnerConfirmationGate confirmationGate;
    @Mock private fr.claudegateway.runner.audit.RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;
    private Workspace workspace;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final List<String> started = java.util.Collections.synchronizedList(new ArrayList<>());

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
        service.setParallelTools(4);

        when(confirmationGate.await(any(), any(), anyString(), any())).thenAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            return new RunnerConfirmationGate.Outcome(RunnerConfirmationGate.Decision.ALLOW, null);
        });
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

        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setHostId(hostId);
        workspace.setProjectPath("projet");
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        workspace.setAgentAskBeforeBash(false);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(runnerHostService.bashConcurrency(hostId)).thenReturn(4);

        // Chaque commande dure COMMAND_MS et diffuse une ligne : on mesure combien tournent ensemble.
        when(runnerToolGateway.bash(any(), anyString(), anyString(), any(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    String command = invocation.getArgument(2);
                    @SuppressWarnings("unchecked")
                    Consumer<String> onOutput = invocation.getArgument(5);
                    return slow(command, onOutput);
                });
        when(runnerToolGateway.writeFile(any(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            started.add("write");
            return new RunnerCallResult(true, "écrit", false, null, 5L, null, null, null, "", false);
        });
    }

    private RunnerCallResult slow(String command, Consumer<String> onOutput) throws InterruptedException {
        started.add(command);
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try {
            if (onOutput != null) {
                onOutput.accept("sortie de " + command + "\n");
            }
            Thread.sleep(COMMAND_MS);
        } finally {
            inFlight.decrementAndGet();
        }
        return new RunnerCallResult(true, "", false, 0, COMMAND_MS, null, null, null,
                "résultat de " + command, false);
    }

    private static AgentToolCall bash(String id, String command) {
        ObjectNode input = MAPPER.createObjectNode().put("command", command);
        return new AgentToolCall(id, "bash", input);
    }

    private static AgentToolCall write(String id, String path) {
        ObjectNode input = MAPPER.createObjectNode().put("path", path).put("content", "x");
        return new AgentToolCall(id, "write_file", input);
    }

    private void oneResponseCalling(AgentToolCall... calls) {
        agentProvider.enqueueTurn(new AgentTurn("", List.of(calls), false, 5, 5));
        agentProvider.enqueueFinal("Fait.");
    }

    /** Les tool_result du dernier message de résultats, dans l'ordre où le modèle les reçoit. */
    private List<AgentContentBlock.ToolResult> lastToolResults() {
        List<AgentContentBlock.ToolResult> last = new ArrayList<>();
        for (AgentMessage message : agentProvider.lastRequest.messages()) {
            List<AgentContentBlock.ToolResult> here = new ArrayList<>();
            for (AgentContentBlock block : message.content()) {
                if (block instanceof AgentContentBlock.ToolResult result) {
                    here.add(result);
                }
            }
            if (!here.isEmpty()) {
                last = here;
            }
        }
        return last;
    }

    @Test
    void independentReadCommandsRunTogetherAndKeepTheirOrder() {
        oneResponseCalling(bash("t1", "git status"), bash("t2", "ls -la"), bash("t3", "cat README.md"));

        long begin = System.currentTimeMillis();
        service.chat(userId, workspaceId, "trois sujets");
        long elapsed = System.currentTimeMillis() - begin;

        assertThat(maxInFlight.get()).as("les trois lectures ont tourné ensemble").isEqualTo(3);
        assertThat(elapsed).isLessThan(3 * COMMAND_MS);
        List<AgentContentBlock.ToolResult> results = lastToolResults();
        assertThat(results).extracting(AgentContentBlock.ToolResult::toolUseId).containsExactly("t1", "t2", "t3");
        assertThat(results.get(0).content()).contains("résultat de git status");
        assertThat(results.get(1).content()).contains("résultat de ls -la");
        assertThat(results.get(2).content()).contains("résultat de cat README.md");
    }

    @Test
    void aWriteIsABarrierNothingAfterItStartsBeforeIt() {
        oneResponseCalling(bash("t1", "cat a.txt"), bash("t2", "cat b.txt"), write("t3", "c.txt"),
                bash("t4", "cat c.txt"), bash("t5", "cat d.txt"));

        service.chat(userId, workspaceId, "lire, écrire, relire");

        assertThat(maxInFlight.get()).isEqualTo(2);
        int writeAt = started.indexOf("write");
        assertThat(started.subList(0, writeAt)).containsExactlyInAnyOrder("cat a.txt", "cat b.txt");
        assertThat(started.subList(writeAt + 1, started.size()))
                .containsExactlyInAnyOrder("cat c.txt", "cat d.txt");
        assertThat(lastToolResults()).extracting(AgentContentBlock.ToolResult::toolUseId)
                .containsExactly("t1", "t2", "t3", "t4", "t5");
    }

    @Test
    void anOldRunnerReceivesOneCommandAtATime() {
        when(runnerHostService.bashConcurrency(hostId)).thenReturn(1);
        oneResponseCalling(bash("t1", "git status"), bash("t2", "ls"));

        service.chat(userId, workspaceId, "deux lectures");

        assertThat(maxInFlight.get()).isEqualTo(1);
    }

    @Test
    void aCommandThatMayModifyIsNeverGrouped() {
        oneResponseCalling(bash("t1", "mvn test"), bash("t2", "npm install"));

        service.chat(userId, workspaceId, "build");

        assertThat(maxInFlight.get()).isEqualTo(1);
    }

    @Test
    void aCommandThatAsksForPermissionIsNeverGrouped() {
        workspace.setAgentAskBeforeBash(true);
        oneResponseCalling(bash("t1", "git status"), bash("t2", "ls"));

        service.chat(userId, workspaceId, "deux lectures");

        assertThat(maxInFlight.get()).isEqualTo(1);
    }

    @Test
    void zeroIsACircuitBreakerEverythingStaysSequential() {
        service.setParallelTools(0);
        oneResponseCalling(bash("t1", "git status"), bash("t2", "ls"), bash("t3", "pwd"));

        service.chat(userId, workspaceId, "trois lectures");

        assertThat(maxInFlight.get()).isEqualTo(1);
        assertThat(lastToolResults()).extracting(AgentContentBlock.ToolResult::toolUseId)
                .containsExactly("t1", "t2", "t3");
    }

    @Test
    void eachCommandStreamsItsOwnOutputInTheOrderOfTheCalls() {
        List<String> shown = java.util.Collections.synchronizedList(new ArrayList<>());
        oneResponseCalling(bash("t1", "git status"), bash("t2", "ls"));

        service.chatStreaming(userId, workspaceId, "deux lectures", new AtelierProgressListener() {
            @Override
            public void onAction(AtelierStepEvent step) {
                shown.add("étape");
            }

            @Override
            public void onText(String text) {
            }

            @Override
            public void onOutput(String chunk) {
                shown.add(chunk);
            }
        });

        assertThat(shown).containsSubsequence("sortie de git status\n", "sortie de ls\n");
        assertThat(String.join("|", shown)).doesNotContain("sortie de ls\n|sortie de git status");
    }
}
