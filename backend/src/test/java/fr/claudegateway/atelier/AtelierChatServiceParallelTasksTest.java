package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * F-195 / SF-195-04 — plusieurs sous-tâches ÉCRIVAINES d'une même réponse tournent ensemble (chacune
 * son worktree), leurs synthèses reviennent dans l'ordre d'émission, un chevauchement de fichiers est
 * signalé ; le plafond de délégations vaut par demande (première + interjections).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceParallelTasksTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long SUBLOOP_MS = 300L;

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
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicInteger mainCalls = new AtomicInteger();

    /** Réponses successives de la boucle PRINCIPALE ; les sous-boucles répondent selon leur consigne. */
    private final List<AgentTurn> mainScript = new ArrayList<>();
    private volatile AgentTurnRequest lastMainRequest;

    private AtelierChatService service;

    private final AiAgentProvider provider = this::answer;

    private AgentTurn answer(AgentTurnRequest request) {
        boolean main = request.tools().stream().anyMatch(tool -> "task".equals(tool.name()));
        if (main) {
            lastMainRequest = request;
            int index = mainCalls.getAndIncrement();
            return index < mainScript.size() ? mainScript.get(index)
                    : new AgentTurn("Terminé.", List.of(), true, 5, 5);
        }
        // Sous-boucle d'une sous-tâche : elle « travaille » SUBLOOP_MS puis rend sa synthèse.
        String prompt = firstUserText(request);
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try {
            Thread.sleep(SUBLOOP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            inFlight.decrementAndGet();
        }
        return new AgentTurn("Synthèse de " + prompt, List.of(), true, 5, 5);
    }

    private static String firstUserText(AgentTurnRequest request) {
        for (AgentMessage message : request.messages()) {
            for (AgentContentBlock block : message.content()) {
                if (block instanceof AgentContentBlock.Text text) {
                    if (text.text().contains("SUJET-A")) {
                        return "SUJET-A";
                    }
                    if (text.text().contains("SUJET-B")) {
                        return "SUJET-B";
                    }
                }
            }
        }
        return "?";
    }

    @BeforeEach
    void setUp() {
        build(null);
        when(confirmationGate.await(any(), any(), anyString(), any())).thenAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            return new fr.claudegateway.runner.exec.RunnerConfirmationGate.Outcome(
                    fr.claudegateway.runner.exec.RunnerConfirmationGate.Decision.ALLOW, null);
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
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setHostId(hostId);
        workspace.setProjectPath("projet");
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);

        when(runnerToolGateway.worktreeCreate(any(), anyString(), anyString())).thenAnswer(invocation ->
                ok("{\"worktreePath\":\".atelier-worktrees/" + invocation.getArgument(2) + "\"}"));
        when(runnerToolGateway.worktreeRemove(any(), anyString(), anyString())).thenReturn(ok(""));
        when(runnerToolGateway.worktreeFinalize(any(), anyString(), anyString(), any())).thenAnswer(invocation ->
                ok("{\"branch\":\"atelier/task/" + invocation.getArgument(2) + "\",\"committed\":true,"
                        + "\"hasChanges\":true,\"diffStat\":\" src/commun.txt | 2 +-\"}"));
    }

    private void build(Integer maxDelegations) {
        service = new AtelierChatService(workspaceService, messageRepository, provider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null,
                        maxDelegations, true));
        service.setTaskParallelism(3);
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false);
    }

    private static AgentToolCall task(String id, String prompt) {
        return new AgentToolCall(id, "task", MAPPER.createObjectNode().put("prompt", prompt));
    }

    private List<AgentContentBlock.ToolResult> allToolResults() {
        List<AgentContentBlock.ToolResult> all = new ArrayList<>();
        for (AgentMessage message : lastMainRequest.messages()) {
            for (AgentContentBlock block : message.content()) {
                if (block instanceof AgentContentBlock.ToolResult result) {
                    all.add(result);
                }
            }
        }
        return all;
    }

    @Test
    void twoWriterTasksRunTogetherAndReturnInEmissionOrder() {
        mainScript.add(new AgentTurn("", List.of(task("tA", "SUJET-A"), task("tB", "SUJET-B")), false, 5, 5));

        long begin = System.currentTimeMillis();
        service.chat(userId, workspaceId, "deux sujets");
        long elapsed = System.currentTimeMillis() - begin;

        assertThat(maxInFlight.get()).as("les deux sous-tâches ont tourné ensemble").isEqualTo(2);
        assertThat(elapsed).isLessThan(2 * SUBLOOP_MS + 250);
        List<AgentContentBlock.ToolResult> results = allToolResults();
        assertThat(results).extracting(AgentContentBlock.ToolResult::toolUseId).containsExactly("tA", "tB");
        assertThat(results.get(0).content()).contains("Synthèse de SUJET-A").doesNotContain("Attention");
        // Même fichier touché par les deux : signalé sur la seconde, jamais écrasé.
        assertThat(results.get(1).content()).contains("Synthèse de SUJET-B")
                .contains("Attention : une autre sous-tâche de ce tour a aussi modifié src/commun.txt")
                .contains("rien n'a été écrasé");
    }

    @Test
    void zeroKeepsWriterTasksOneAfterTheOther() {
        service.setTaskParallelism(0);
        mainScript.add(new AgentTurn("", List.of(task("tA", "SUJET-A"), task("tB", "SUJET-B")), false, 5, 5));

        service.chat(userId, workspaceId, "deux sujets");

        assertThat(maxInFlight.get()).isEqualTo(1);
        assertThat(allToolResults()).extracting(AgentContentBlock.ToolResult::toolUseId)
                .containsExactly("tA", "tB");
    }

    @Test
    void theDelegationCapIsBoundedEvenWhenGrouped() {
        build(1);
        mainScript.add(new AgentTurn("", List.of(task("tA", "SUJET-A"), task("tB", "SUJET-B")), false, 5, 5));

        service.chat(userId, workspaceId, "deux sujets");

        List<AgentContentBlock.ToolResult> results = allToolResults();
        assertThat(results.get(0).content()).contains("Synthèse de SUJET-A");
        assertThat(results.get(1).content()).contains("Limite de délégations atteinte");
    }

    @Test
    void anInterjectionOpensItsOwnDelegationBudget() {
        build(1);
        mainScript.add(new AgentTurn("", List.of(task("tA", "SUJET-A")), false, 5, 5));
        mainScript.add(new AgentTurn("", List.of(task("tB", "SUJET-B")), false, 5, 5));
        AtomicInteger takes = new AtomicInteger();

        service.chatStreaming(userId, workspaceId, "premier sujet", new AtelierProgressListener() {
            @Override
            public void onAction(AtelierStepEvent step) {
            }

            @Override
            public void onText(String text) {
            }

            @Override
            public List<AtelierSteer> takeSteers() {
                // Une interjection arrive pendant le premier sujet (après son premier appel).
                return takes.incrementAndGet() == 2
                        ? List.of(new AtelierSteer("s1", "et aussi le sujet B", System.currentTimeMillis()))
                        : List.of();
            }
        });

        List<AgentContentBlock.ToolResult> results = allToolResults();
        assertThat(results).hasSize(2);
        assertThat(results.get(1).content()).contains("Synthèse de SUJET-B").doesNotContain("Limite");
    }

    @Test
    void touchedFilesAreReadFromTheDiffSummary() {
        assertThat(AtelierChatService.touchedFiles(
                "Fait.\n\n— Changements isolés sur la branche `b`.\n src/a.txt | 2 +-\n docs/b.md | 10 ++++\n"
                        + " img.png | Bin 0 -> 12 bytes\n 3 files changed"))
                .containsExactly("src/a.txt", "docs/b.md", "img.png");
        assertThat(AtelierChatService.touchedFiles(null)).isEmpty();
    }
}
