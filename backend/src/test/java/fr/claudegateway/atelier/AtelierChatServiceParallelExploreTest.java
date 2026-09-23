package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * Explorations <b>concurrentes</b> (F-39 / SF-39-21) : quand un tour émet plusieurs {@code explore}
 * indépendants, la boucle principale les exécute ensemble via un pool borné, sans rien changer
 * d'autre — lecture seule, coût imputé au tour, ordre des {@code tool_result} par appel.
 *
 * <p>Le provider de test n'est pas le stub FIFO habituel : sous concurrence, un unique script partagé
 * serait pillé par les sous-boucles en course. Ici il répond <b>par question</b> (déterministe) et
 * instrumente la concurrence — barrière pour prouver le recouvrement, compteur pour prouver le
 * plafond.</p>
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceParallelExploreTest {

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

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private ScriptedParallelProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ScriptedParallelProvider();
        stubHappyPath();
    }

    private AtelierChatService serviceWith(int exploreParallelism) {
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
                        exploreParallelism));
    }

    private void stubHappyPath() {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(quotaService.currentUsage(userId)).thenReturn(
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

    // ------------------------------------------------- recouvrement temporel + ordre par appel

    @Test
    void twoExploresOfOneTurnRunConcurrentlyAndReturnInCallOrder() {
        // Deux explorations d'un même tour, parallélisme 2. La barrière(2) ne se lève QUE si les deux
        // sous-boucles y arrivent ensemble : une exécution en série y bloquerait la première jusqu'au
        // délai, son exploration échouerait, et « ANSWER-Q-ALPHA » manquerait. La présence des deux
        // réponses est donc la preuve du recouvrement.
        provider.enqueueExplores("q-alpha", "q-beta");
        provider.gate = new CyclicBarrier(2);
        // Après la barrière, alpha traîne : beta finit d'abord. L'ordre des tool_result doit RESTER
        // celui des appels (alpha avant beta), pas celui des fins.
        provider.delayAfterGateMs.put("q-alpha", 120L);

        AtelierChatResult result = serviceWith(2).chat(userId, workspaceId, "explore deux choses");

        assertThat(result.reply()).isEqualTo("done");
        // Les deux sous-boucles ont bien tourné en même temps (barrière levée) : les deux conclusions
        // sont revenues.
        String finalConvo = provider.lastMainConvo;
        assertThat(finalConvo).contains("ANSWER-Q-ALPHA").contains("ANSWER-Q-BETA");
        // Ordre des tool_result = ordre des APPELS (alpha puis beta), quel que soit l'ordre de fin (D6).
        assertThat(finalConvo.indexOf("ANSWER-Q-ALPHA")).isLessThan(finalConvo.indexOf("ANSWER-Q-BETA"));
        // La concurrence observée a bien atteint 2.
        assertThat(provider.maxActive.get()).isEqualTo(2);
    }

    // ------------------------------------------------- coût imputé au tour, exact sous concurrence

    @Test
    void theCostOfEveryConcurrentExplorationIsChargedToTheTurn() {
        // Deux sous-boucles à 100 tokens d'entrée / 10 de sortie chacune, plus les deux tours de la
        // boucle principale (5/5). Le total du tour doit tout porter — déléguer, même en parallèle, ne
        // fait jamais passer sous le plafond par message (D4).
        provider.enqueueExplores("q-alpha", "q-beta");
        provider.gate = new CyclicBarrier(2);

        AtelierChatResult result = serviceWith(2).chat(userId, workspaceId, "explore deux choses");

        // 2×100 (sous-boucles) + 2×5 (boucle principale) = 210 ; 2×10 + 2×5 = 30.
        assertThat(result.inputTokens()).isEqualTo(210L);
        assertThat(result.outputTokens()).isEqualTo(30L);
    }

    // ------------------------------------------------- plafond par message (maxDelegations)

    @Test
    void aSixthExploreInOneTurnHitsThePerMessageCapWithoutBreakingTheTurn() {
        // maxDelegations défaut = 5 (F-148 / SF-148-01) : sur six explorations d'un même tour, cinq
        // s'exécutent, la sixième reçoit l'erreur de limite — le tour aboutit quand même.
        provider.enqueueExplores("q-a", "q-b", "q-c", "q-d", "q-e", "q-f");

        AtelierChatResult result = serviceWith(3).chat(userId, workspaceId, "explore beaucoup");

        assertThat(result.reply()).isEqualTo("done");
        String finalConvo = provider.lastMainConvo;
        assertThat(finalConvo).contains("ANSWER-Q-A").contains("ANSWER-Q-B").contains("ANSWER-Q-C")
                .contains("ANSWER-Q-D").contains("ANSWER-Q-E");
        // La sixième n'a jamais été lancée : elle porte le message de limite, pas une réponse.
        assertThat(finalConvo).contains("Limite de délégations atteinte");
        assertThat(finalConvo).doesNotContain("ANSWER-Q-F");
        // Au plus cinq sous-boucles ont réellement tourné.
        assertThat(provider.startedQuestions).hasSize(5);
    }

    // ------------------------------------------------- plafond de parallélisme (vagues)

    @Test
    void theParallelismCapIsRespectedSoExploresRunInWaves() {
        // Parallélisme 1 : deux explorations d'un tour ne se recouvrent jamais (un seul thread), mais
        // les deux tournent et reviennent dans l'ordre. Le plafond crée des vagues.
        provider.enqueueExplores("q-alpha", "q-beta");
        provider.holdMs = 30L; // élargit la fenêtre où un chevauchement, s'il existait, se verrait.

        AtelierChatResult result = serviceWith(1).chat(userId, workspaceId, "explore deux choses");

        assertThat(result.reply()).isEqualTo("done");
        // Jamais plus d'une sous-boucle en vol : le pool d'un thread l'interdit par construction.
        assertThat(provider.maxActive.get()).isEqualTo(1);
        String finalConvo = provider.lastMainConvo;
        assertThat(finalConvo.indexOf("ANSWER-Q-ALPHA")).isLessThan(finalConvo.indexOf("ANSWER-Q-BETA"));
    }

    @Test
    void moreExploresThanTheCapNeverExceedItButAllComplete() {
        // Trois explorations, plafond 2 : la concurrence observée ne dépasse jamais 2 (deux vagues),
        // et les trois conclusions reviennent dans l'ordre des appels.
        provider.enqueueExplores("q-a", "q-b", "q-c");
        provider.holdMs = 30L;

        AtelierChatResult result = serviceWith(2).chat(userId, workspaceId, "explore trois choses");

        assertThat(result.reply()).isEqualTo("done");
        assertThat(provider.maxActive.get()).isLessThanOrEqualTo(2);
        String finalConvo = provider.lastMainConvo;
        assertThat(finalConvo).contains("ANSWER-Q-A").contains("ANSWER-Q-B").contains("ANSWER-Q-C");
        assertThat(finalConvo.indexOf("ANSWER-Q-A")).isLessThan(finalConvo.indexOf("ANSWER-Q-B"));
        assertThat(finalConvo.indexOf("ANSWER-Q-B")).isLessThan(finalConvo.indexOf("ANSWER-Q-C"));
    }

    // ------------------------------------------------- isolation des échecs

    @Test
    void oneExplorationFailingDoesNotKillTheOthers() {
        // q-beta lève dans sa sous-boucle : elle rend SON erreur comme SON tool_result, sans empêcher
        // q-alpha de réussir ni casser le tour (D5).
        provider.enqueueExplores("q-alpha", "q-beta");
        provider.failQuestions.add("q-beta");

        AtelierChatResult result = serviceWith(2).chat(userId, workspaceId, "explore deux choses");

        assertThat(result.reply()).isEqualTo("done");
        String finalConvo = provider.lastMainConvo;
        assertThat(finalConvo).contains("ANSWER-Q-ALPHA");
        assertThat(finalConvo).contains("L'exploration a échoué");
    }

    // ------------------------------------------------- lecture seule inviolée sous concurrence

    @Test
    void everyConcurrentExplorationOnlyEverGetsReadTools() {
        provider.enqueueExplores("q-alpha", "q-beta");
        provider.gate = new CyclicBarrier(2);

        serviceWith(2).chat(userId, workspaceId, "explore deux choses");

        // Chaque sous-boucle n'a reçu QUE des outils de lecture : aucune écriture, aucune commande —
        // la garantie de SF-39-14 (D2) tient sous concurrence.
        assertThat(provider.subLoopToolBelts).isNotEmpty();
        for (List<String> belt : provider.subLoopToolBelts) {
            assertThat(belt).containsExactly("list_files", "read_file", "search_files", "grep", "glob");
            assertThat(belt).doesNotContain("bash", "write_file", "edit_file");
        }
    }

    /**
     * Provider scriptable et <b>thread-safe</b> : la boucle principale reçoit un tour d'{@code explore}
     * groupés puis un tour final ; chaque sous-boucle (reconnue par le prompt système « Tu explores »)
     * répond selon SA question, en instrumentant la concurrence.
     */
    private static final class ScriptedParallelProvider implements AiAgentProvider {

        private final ObjectMapper mapper = new ObjectMapper();
        private final List<String> questions = new ArrayList<>();
        private final AtomicInteger mainCalls = new AtomicInteger();

        // Instrumentation de concurrence.
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        final java.util.Set<String> startedQuestions = ConcurrentHashMap.newKeySet();
        final List<List<String>> subLoopToolBelts = new CopyOnWriteArrayList<>();

        // Scénarios.
        volatile CyclicBarrier gate;
        volatile long holdMs = 0L;
        final java.util.Map<String, Long> delayAfterGateMs = new ConcurrentHashMap<>();
        final java.util.Set<String> failQuestions = ConcurrentHashMap.newKeySet();

        // Dernière conversation vue par la boucle principale (porte les tool_result du tour final).
        volatile String lastMainConvo;

        void enqueueExplores(String... qs) {
            questions.clear();
            for (String q : qs) {
                questions.add(q);
            }
        }

        @Override
        public AgentTurn nextTurn(AgentTurnRequest request) {
            String system = request.system();
            boolean subLoop = system != null && system.startsWith("Tu explores");
            if (subLoop) {
                return runSubLoop(request);
            }
            lastMainConvo = String.valueOf(request.messages());
            if (mainCalls.getAndIncrement() == 0) {
                // Premier tour : le modèle émet plusieurs `explore` dans le même tour.
                List<AgentToolCall> calls = new ArrayList<>();
                for (int i = 0; i < questions.size(); i++) {
                    ObjectNode input = mapper.createObjectNode();
                    input.put("question", questions.get(i));
                    calls.add(new AgentToolCall("explore-" + i, "explore", input));
                }
                return new AgentTurn("", calls, false, 5, 5);
            }
            // Tours suivants : la boucle principale conclut.
            return new AgentTurn("done", List.of(), true, 5, 5);
        }

        private AgentTurn runSubLoop(AgentTurnRequest request) {
            String convo = String.valueOf(request.messages());
            String question = questions.stream().filter(convo::contains).findFirst().orElse("?");
            startedQuestions.add(question);
            List<String> belt = new ArrayList<>();
            if (request.tools() != null) {
                request.tools().forEach(tool -> belt.add(tool.name()));
            }
            subLoopToolBelts.add(belt);
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try {
                if (gate != null) {
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException | BrokenBarrierException | TimeoutException ex) {
                        // Exécution non concurrente : la barrière n'a pas pu se lever à temps. On lève
                        // pour que l'exploration soit vue comme un échec (et le test le détecte).
                        throw new IllegalStateException("barrière non levée : exécution non concurrente");
                    }
                }
                sleepQuietly(holdMs);
                sleepQuietly(delayAfterGateMs.getOrDefault(question, 0L));
                if (failQuestions.contains(question)) {
                    throw new IllegalStateException("panne simulée de la sous-boucle " + question);
                }
                return new AgentTurn("ANSWER-" + question.toUpperCase(java.util.Locale.ROOT),
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
