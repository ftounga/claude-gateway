package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * Parallélisme <b>lecture seule</b> (F-121 / SF-121-14) : la sous-tâche {@code task} marquée
 * {@code read_only} rejoint le fan-out concurrent déjà livré pour {@code explore} (SF-39-21) — même
 * pool borné, même ordre par appel, même imputation du coût au tour —, tandis que la sous-tâche qui
 * <b>écrit</b> reste sérielle avec son worktree isolé.
 *
 * <p>Le provider de test répond <b>par clé</b> (déterministe) plutôt qu'en FIFO : sous concurrence, un
 * script partagé serait pillé par les sous-boucles en course.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceParallelReadTaskTest {

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

    private static final String WORKTREE_JSON =
            "{\"worktreePath\":\".atelier-worktrees/wt1\",\"branch\":\"atelier/task/wt1\"}";

    private ScriptedParallelProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ScriptedParallelProvider();
        stubHappyPath();
    }

    private AtelierChatService serviceWith(int parallelism) {
        return new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) provider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                runnerHostService,
                // storageExecution=true (13ᵉ) ; explore-parallelism (23ᵉ) réglé par le test.
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, true, null, null, null, null, null, null, null, null, null,
                        parallelism));
    }

    /** Un poste connecté : c'est la seule cible où {@code task} est déclaré et exécutable. */
    private void stubRunnerWorkspace() {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setHostId(hostId);
        workspace.setProjectPath("projet");
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
    }

    private void stubHappyPath() {
        stubRunnerWorkspace();
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
        when(confirmationGate.await(any(), any(), anyString(), any())).thenAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            return new fr.claudegateway.runner.exec.RunnerConfirmationGate.Outcome(
                    fr.claudegateway.runner.exec.RunnerConfirmationGate.Decision.ALLOW, null);
        });
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false);
    }

    // ------------------------------------------------- recouvrement + ordre par appel

    @Test
    void twoReadOnlyTasksOfOneTurnRunConcurrentlyAndReturnInCallOrder() {
        // La barrière(2) ne se lève QUE si les deux sous-boucles y arrivent ensemble : en série, la
        // première y expirerait, sa sous-tâche échouerait, et « SYNTHESE-T-ALPHA » manquerait.
        provider.enqueueReadOnlyTasks("t-alpha", "t-beta");
        provider.gate = new CyclicBarrier(2);
        // Après la barrière, alpha traîne : beta finit d'abord. L'ordre des tool_result doit RESTER
        // celui des appels (alpha avant beta), pas celui des fins.
        provider.delayAfterGateMs.put("t-alpha", 120L);

        AtelierChatResult result = serviceWith(2).chat(userId, workspaceId, "délègue deux lectures");

        assertThat(result.reply()).isEqualTo("done");
        String convo = provider.lastMainConvo;
        assertThat(convo).contains("SYNTHESE-T-ALPHA").contains("SYNTHESE-T-BETA");
        assertThat(convo.indexOf("SYNTHESE-T-ALPHA")).isLessThan(convo.indexOf("SYNTHESE-T-BETA"));
        assertThat(provider.maxActive.get()).isEqualTo(2);
    }

    @Test
    void anExploreAndAReadOnlyTaskOfTheSameTurnShareTheSamePool() {
        // Le fan-out ne distingue pas les deux voies : une exploration et une sous-tâche en lecture
        // seule émises dans le même tour s'exécutent ENSEMBLE.
        provider.enqueueMixed(List.of(new Call("explore", "q-alpha"), new Call("read", "t-beta")));
        provider.gate = new CyclicBarrier(2);

        AtelierChatResult result = serviceWith(2).chat(userId, workspaceId, "lis deux choses");

        assertThat(result.reply()).isEqualTo("done");
        assertThat(provider.maxActive.get()).isEqualTo(2);
        String convo = provider.lastMainConvo;
        assertThat(convo).contains("ANSWER-Q-ALPHA").contains("SYNTHESE-T-BETA");
        assertThat(convo.indexOf("ANSWER-Q-ALPHA")).isLessThan(convo.indexOf("SYNTHESE-T-BETA"));
    }

    // ------------------------------------------------- pas de worktree en lecture seule

    @Test
    void aReadOnlyTaskNeverCreatesAWorktree() {
        provider.enqueueReadOnlyTasks("t-alpha", "t-beta");
        provider.gate = new CyclicBarrier(2);

        serviceWith(2).chat(userId, workspaceId, "délègue deux lectures");

        // Rien n'écrit : il n'y a rien à isoler — aucune création, aucun démontage, aucun git.
        verify(runnerToolGateway, never()).worktreeCreate(any(), anyString(), anyString());
        verify(runnerToolGateway, never()).worktreeRemove(any(), anyString(), anyString());
    }

    @Test
    void theWritingTaskOfTheSameTurnStaysSerialWithItsWorktree() {
        // Mutations en série (cadrage) : la sous-tâche écrivaine garde EXACTEMENT son chemin — son
        // worktree isolé — pendant que la lecture, elle, est passée par le pré-passage parallèle.
        when(runnerToolGateway.worktreeCreate(any(), anyString(), anyString())).thenReturn(ok(WORKTREE_JSON));
        when(runnerToolGateway.worktreeRemove(any(), anyString(), anyString())).thenReturn(ok(""));
        provider.enqueueMixed(List.of(new Call("read", "t-lecture"), new Call("write", "t-ecriture")));

        AtelierChatResult result = serviceWith(2).chat(userId, workspaceId, "lis puis écris");

        assertThat(result.reply()).isEqualTo("done");
        // Un seul worktree : celui de la sous-tâche écrivaine.
        verify(runnerToolGateway).worktreeCreate(any(), anyString(), anyString());
        verify(runnerToolGateway).worktreeRemove(any(), anyString(), anyString());
        String convo = provider.lastMainConvo;
        assertThat(convo).contains("SYNTHESE-T-LECTURE").contains("SYNTHESE-T-ECRITURE");
        // La sous-boucle écrivaine a bien reçu la panoplie complète, la lectrice non.
        assertThat(provider.toolBelts.get("t-ecriture")).contains("write_file", "bash");
        assertThat(provider.toolBelts.get("t-lecture")).doesNotContain("write_file", "bash");
    }

    // ------------------------------------------------- lecture seule inviolée

    @Test
    void theReadOnlySubLoopOnlyGetsReadToolsAndAnyMutatingCallIsRefused() {
        provider.enqueueReadOnlyTasks("t-alpha");
        provider.mutatingAttempts.add("t-alpha"); // la sous-boucle tente un write_file

        AtelierChatResult result = serviceWith(2).chat(userId, workspaceId, "audite");

        assertThat(result.reply()).isEqualTo("done");
        // Panoplie construite : uniquement de la lecture (double verrou, 1er cran).
        assertThat(provider.toolBelts.get("t-alpha"))
                .containsExactly("list_files", "read_file", "search_files", "grep", "glob");
        // Et même si le modèle demande une écriture, elle est REFUSÉE (2ᵉ cran) : rien ne part au poste.
        assertThat(provider.mutatingResults).isNotEmpty();
        assertThat(provider.mutatingResults.get(0)).contains("Outil indisponible en sous-tâche de lecture");
        verify(runnerToolGateway, never()).writeFile(any(), anyString(), anyString(), anyString());
    }

    // ------------------------------------------------- coût imputé au tour

    @Test
    void theCostOfEveryConcurrentReadOnlyTaskIsChargedToTheTurn() {
        // 2×(100/10) pour les sous-boucles + 2×(5/5) pour les deux tours de la boucle principale.
        provider.enqueueReadOnlyTasks("t-alpha", "t-beta");
        provider.gate = new CyclicBarrier(2);

        AtelierChatResult result = serviceWith(2).chat(userId, workspaceId, "délègue deux lectures");

        assertThat(result.inputTokens()).isEqualTo(210L);
        assertThat(result.outputTokens()).isEqualTo(30L);
    }

    // ------------------------------------------------- isolation des échecs & vagues

    @Test
    void oneReadOnlyTaskFailingDoesNotKillTheOthers() {
        provider.enqueueReadOnlyTasks("t-alpha", "t-beta");
        provider.failKeys.add("t-beta");

        AtelierChatResult result = serviceWith(2).chat(userId, workspaceId, "délègue deux lectures");

        assertThat(result.reply()).isEqualTo("done");
        String convo = provider.lastMainConvo;
        assertThat(convo).contains("SYNTHESE-T-ALPHA");
        assertThat(convo).contains("La sous-tâche de lecture a échoué");
    }

    @Test
    void theParallelismCapIsRespectedSoReadOnlyTasksRunInWaves() {
        provider.enqueueReadOnlyTasks("t-alpha", "t-beta");
        provider.holdMs = 30L;

        AtelierChatResult result = serviceWith(1).chat(userId, workspaceId, "délègue deux lectures");

        assertThat(result.reply()).isEqualTo("done");
        assertThat(provider.maxActive.get()).isEqualTo(1);
        String convo = provider.lastMainConvo;
        assertThat(convo.indexOf("SYNTHESE-T-ALPHA")).isLessThan(convo.indexOf("SYNTHESE-T-BETA"));
    }

    // ------------------------------------------------- le drapeau est déclaré au modèle

    @Test
    void theTaskToolDeclaresTheReadOnlyFlagAndTheGroupingDoctrine() {
        provider.enqueueMixed(List.of());

        serviceWith(2).chat(userId, workspaceId, "bonjour");

        AgentTool task = provider.lastMainTools.stream()
                .filter(tool -> "task".equals(tool.name())).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> properties =
                (java.util.Map<String, Object>) task.inputSchema().get("properties");
        assertThat(properties).containsKey("read_only");
        assertThat(task.description()).contains("read_only");
        assertThat(task.description()).contains("EN PARALLÈLE");
    }

    /** Un appel à émettre dans le tour : {@code explore}, {@code read} (task read_only) ou {@code write}. */
    private record Call(String kind, String key) {
    }

    /**
     * Provider scriptable et <b>thread-safe</b> : la boucle principale reçoit un tour d'appels groupés
     * puis un tour final ; chaque sous-boucle est reconnue à sa consigne système et répond selon SA
     * clé, en instrumentant la concurrence.
     */
    private static final class ScriptedParallelProvider implements AiAgentProvider {

        private final ObjectMapper mapper = new ObjectMapper();
        private final List<Call> calls = new ArrayList<>();
        private final AtomicInteger mainCalls = new AtomicInteger();

        // Instrumentation de concurrence.
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        final java.util.Map<String, List<String>> toolBelts = new ConcurrentHashMap<>();
        final List<String> mutatingResults = new CopyOnWriteArrayList<>();

        // Scénarios.
        volatile CyclicBarrier gate;
        volatile long holdMs = 0L;
        final java.util.Map<String, Long> delayAfterGateMs = new ConcurrentHashMap<>();
        final java.util.Set<String> failKeys = ConcurrentHashMap.newKeySet();
        final java.util.Set<String> mutatingAttempts = ConcurrentHashMap.newKeySet();
        private final java.util.Set<String> mutatingDone = ConcurrentHashMap.newKeySet();

        // Dernière vue de la boucle principale.
        volatile String lastMainConvo;
        volatile List<AgentTool> lastMainTools = List.of();

        void enqueueReadOnlyTasks(String... keys) {
            calls.clear();
            for (String key : keys) {
                calls.add(new Call("read", key));
            }
        }

        void enqueueMixed(List<Call> wanted) {
            calls.clear();
            calls.addAll(wanted);
        }

        @Override
        public AgentTurn nextTurn(AgentTurnRequest request) {
            String system = request.system();
            boolean subLoop = system != null
                    && (system.startsWith("Tu explores") || system.startsWith("Tu es un sous-agent"));
            if (subLoop) {
                return runSubLoop(request);
            }
            lastMainConvo = String.valueOf(request.messages());
            lastMainTools = request.tools() == null ? List.of() : List.copyOf(request.tools());
            if (mainCalls.getAndIncrement() == 0 && !calls.isEmpty()) {
                List<AgentToolCall> emitted = new ArrayList<>();
                for (int i = 0; i < calls.size(); i++) {
                    Call call = calls.get(i);
                    ObjectNode input = mapper.createObjectNode();
                    if ("explore".equals(call.kind())) {
                        input.put("question", call.key());
                        emitted.add(new AgentToolCall("call-" + i, "explore", input));
                    } else {
                        input.put("prompt", call.key());
                        if ("read".equals(call.kind())) {
                            input.put("read_only", true);
                        }
                        emitted.add(new AgentToolCall("call-" + i, "task", input));
                    }
                }
                return new AgentTurn("", emitted, false, 5, 5);
            }
            return new AgentTurn("done", List.of(), true, 5, 5);
        }

        private AgentTurn runSubLoop(AgentTurnRequest request) {
            String convo = String.valueOf(request.messages());
            String key = calls.stream().map(Call::key).filter(convo::contains).findFirst().orElse("?");
            List<String> belt = new ArrayList<>();
            if (request.tools() != null) {
                request.tools().forEach(tool -> belt.add(tool.name()));
            }
            toolBelts.put(key, belt);
            // Les résultats d'outils déjà rendus à CETTE sous-boucle (2ᵉ tour) : on y lit le refus.
            if (convo.contains("Outil indisponible en sous-tâche de lecture")) {
                mutatingResults.add("Outil indisponible en sous-tâche de lecture");
            }
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try {
                if (gate != null) {
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException | BrokenBarrierException | TimeoutException ex) {
                        throw new IllegalStateException("barrière non levée : exécution non concurrente");
                    }
                }
                sleepQuietly(holdMs);
                sleepQuietly(delayAfterGateMs.getOrDefault(key, 0L));
                if (failKeys.contains(key)) {
                    throw new IllegalStateException("panne simulée de la sous-boucle " + key);
                }
                if (mutatingAttempts.contains(key) && mutatingDone.add(key)) {
                    // La sous-boucle en lecture tente une écriture : elle doit être refusée par la
                    // gateway, jamais relayée au poste.
                    List<AgentToolCall> tool = List.of(new AgentToolCall("sub-0", "write_file",
                            mapper.createObjectNode().put("path", "a.txt").put("content", "hop")));
                    return new AgentTurn("", tool, false, 100, 10);
                }
                String prefix = request.system().startsWith("Tu explores") ? "ANSWER-" : "SYNTHESE-";
                return new AgentTurn(prefix + key.toUpperCase(java.util.Locale.ROOT),
                        List.of(), true, 100, 10);
            } finally {
                active.decrementAndGet();
            }
        }

        private static void sleepQuietly(long ms) {
            if (ms <= 0) {
                return;
            }
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
