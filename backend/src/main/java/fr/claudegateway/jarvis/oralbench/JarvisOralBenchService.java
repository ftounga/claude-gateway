package fr.claudegateway.jarvis.oralbench;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentCallShape;
import fr.claudegateway.agent.AgentContextPolicy;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentReasoning;
import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnMode;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.AtelierChatService;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.jarvis.JarvisOralNote;
import fr.claudegateway.jarvis.preferences.JarvisHumour;
import fr.claudegateway.quota.ProviderCostCalculator;
import fr.claudegateway.quota.ProviderPricingProperties;
import fr.claudegateway.quota.TurnCost;
import fr.claudegateway.quota.TurnTokens;
import fr.claudegateway.quota.UsageLedgerService;

/**
 * <b>Le banc de la parole</b> (F-240 / SF-240-02, D12) — <b>administrateur uniquement</b>, à la demande.
 *
 * <p>Joue les 8 scénarios de délégation du jeu figé sur Opus (le modèle du terminal), <b>sans outils</b>, avec la
 * <b>consigne système réelle du terminal</b> d'un sujet du compte et la <b>consigne orale réelle</b>
 * ({@link JarvisOralNote}, dans le message) : on mesure la <b>forme</b> de ce qui serait dit (mots de la ligne 🎙,
 * invitation ciblée, essentiel, confirmation). Un juge dit si la ligne 🎙 donne le verdict d'abord. <b>Plafond</b> :
 * {@value #DEFAULT_CAP_EUR} € par passage (le passage s'arrête avant de le dépasser). Asynchrone : la requête rend un
 * identifiant de passage ; le rapport est rangé en base sous {@code user_id}.</p>
 *
 * <p>Fournisseur via {@link AiAgentProvider} (jamais Anthropic en direct) ; clé du compte via BYOK si elle existe,
 * jamais loggée ni rangée.</p>
 */
@Service
public class JarvisOralBenchService {

    private static final Logger log = LoggerFactory.getLogger(JarvisOralBenchService.class);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static final String DEFAULT_CAP_EUR = "3";
    static final int MAX_OUTPUT_TOKENS = 1_500;
    static final int MAX_STORED_RUNS = 20;
    static final String JUDGE_TOOL = "noter_parole";

    /** Le verdict d'un scénario : longueurs et lecture déterministe, jugement du verdict d'abord, coût. */
    public record ScenarioVerdict(String id, String category, String oral, int words, String invitation,
            boolean genericInvitation, int essentialLines, boolean asksConfirmation, boolean judged,
            boolean verdictFirst, boolean pathTold, String comment, BigDecimal costEur) {
    }

    /** Le rapport d'un passage. */
    public record Report(UUID runId, String status, String model, String judgeModel, UUID workspaceId, int scenarios,
            Integer wordsP50, Integer wordsP90, int verdictFirst, int invitations, int genericInvitations,
            Boolean gatePassed, List<String> gateFailures, BigDecimal costEur, BigDecimal capEur,
            List<ScenarioVerdict> verdicts, OffsetDateTime startedAt, OffsetDateTime finishedAt) {
    }

    private record Run(UUID userId, Report report) {
    }

    private final AiAgentProvider provider;
    private final AtelierChatService chat;
    private final WorkspaceRepository workspaces;
    private final Clock clock;
    private final String model;
    private final String judgeModel;
    private final BigDecimal capEur;
    private final ExecutorService runs = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, Run> byId = new ConcurrentHashMap<>();
    private JarvisOralBenchRunRepository store;
    private ByokKeyService byok;
    private UsageLedgerService ledger;
    private ProviderCostCalculator costs;
    private BigDecimal usdToEur = new BigDecimal("0.92");

    public JarvisOralBenchService(AiAgentProvider provider, AtelierChatService chat, WorkspaceRepository workspaces,
            Clock clock, @Value("${app.jarvis.oral-bench.model:claude-opus-5-5}") String model,
            @Value("${app.jarvis.fast-brain-bench.judge-model:claude-opus-5-5}") String judgeModel,
            @Value("${app.jarvis.oral-bench.cap-eur:" + DEFAULT_CAP_EUR + "}") BigDecimal capEur) {
        this.provider = provider;
        this.chat = chat;
        this.workspaces = workspaces;
        this.clock = clock;
        this.model = model;
        this.judgeModel = judgeModel;
        this.capEur = capEur;
    }

    @Autowired(required = false)
    public void setStore(JarvisOralBenchRunRepository store) {
        this.store = store;
    }

    @Autowired(required = false)
    public void setAccounting(ByokKeyService byok, UsageLedgerService ledger, ProviderCostCalculator costs,
            ProviderPricingProperties pricing) {
        this.byok = byok;
        this.ledger = ledger;
        this.costs = costs;
        if (pricing != null && pricing.usdToEur() != null) {
            this.usdToEur = pricing.usdToEur();
        }
    }

    /**
     * Lance un passage en tâche de fond et rend son identifiant.
     *
     * @param workspaceId le sujet dont la consigne système est prise ; {@code null} = le plus récent du compte
     * @throws IllegalArgumentException aucun sujet au compte
     */
    public UUID start(UUID userId, UUID workspaceId) {
        Workspace workspace = workspaceId != null ? workspaces.findByIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Sujet inconnu."))
                : workspaces.findByUserIdOrderByCreatedAtDesc(userId).stream().findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Aucun sujet : le banc prend la consigne "
                                + "système d'un sujet du compte."));
        UUID runId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        Report running = new Report(runId, "RUNNING", model, judgeModel, workspace.getId(), 0, null, null, 0, 0, 0,
                null, List.of(), BigDecimal.ZERO, capEur, List.of(), now, null);
        byId.put(runId, new Run(userId, running));
        save(userId, running);
        runs.execute(() -> {
            Report report;
            try {
                report = run(userId, runId, workspace.getId(), now);
            } catch (RuntimeException e) {
                log.warn("Banc de la parole en échec ({})", e.getClass().getSimpleName());
                report = new Report(runId, "FAILED", model, judgeModel, workspace.getId(), 0, null, null, 0, 0, 0,
                        false, List.of("panne du banc"), BigDecimal.ZERO, capEur, List.of(), now,
                        OffsetDateTime.now(clock));
            }
            byId.put(runId, new Run(userId, report));
            save(userId, report);
        });
        return runId;
    }

    /** Le rapport d'un passage du compte (jamais celui d'un autre). */
    public Optional<Report> report(UUID userId, UUID runId) {
        Run run = runId == null ? null : byId.get(runId);
        if (run != null) {
            return run.userId().equals(userId) ? Optional.of(run.report()) : Optional.empty();
        }
        if (runId == null || store == null) {
            return Optional.empty();
        }
        return store.findByIdAndUserId(runId, userId).flatMap(r -> {
            try {
                return Optional.of(JSON.readValue(r.getReportJson(), Report.class));
            } catch (Exception e) {
                log.warn("Banc de la parole : rapport illisible ({})", e.getClass().getSimpleName());
                return Optional.empty();
            }
        });
    }

    /** Un passage, de bout en bout (synchrone : appelé en tâche de fond, et directement par les tests). */
    Report run(UUID userId, UUID runId, UUID workspaceId, OffsetDateTime startedAt) {
        // F-240 / SF-240-06 — le jeu daté par rapport au jour même du passage, dans le fuseau du pod : celui de la
        // « Date du jour » que porte la consigne système du terminal (jamais un « hier » périmé).
        List<JarvisOralBench.Scenario> scenarios = JarvisOralBench
                .load(java.time.LocalDate.now(clock.withZone(java.time.ZoneId.systemDefault())));
        String system = chat.terminalSystemPrompt(userId, workspaceId);
        String apiKey = byok == null ? null : byok.resolveActiveApiKey(userId).orElse(null);
        List<ScenarioVerdict> verdicts = new ArrayList<>();
        List<JarvisOralBench.Reading> readings = new ArrayList<>();
        List<String> stops = new ArrayList<>();
        List<String> personal = new ArrayList<>();
        BigDecimal spent = BigDecimal.ZERO;
        BigDecimal last = BigDecimal.ZERO;
        int judged = 0;
        int verdictFirst = 0;
        for (JarvisOralBench.Scenario s : scenarios) {
            // Le plafond : on ne lance pas un appel qui le dépasserait (estimé sur le précédent).
            if (spent.add(last).compareTo(capEur) > 0) {
                stops.add("plafond de " + capEur + " € atteint avant " + s.id());
                break;
            }
            // F-241 / SF-241-03 (D6) — un scénario personnel reçoit son portrait FICTIF, comme un tour Jarvis réel.
            String message = JarvisOralBench.message(s, fr.claudegateway.jarvis.portrait.JarvisPortraitNote
                    .appendForOpus(JarvisOralNote.appendTo(s.said(), JarvisHumour.LIGHT, false), s.fixture()));
            AgentTurn turn = provider.nextTurn(new AgentTurnRequest(model, system,
                    List.of(AgentMessage.userText(message)), List.of(), apiKey, AgentReasoning.none(),
                    AgentContextPolicy.none(), AgentTurnMode.ACT, new AgentCallShape(MAX_OUTPUT_TOKENS, false, false)));
            BigDecimal cost = account(userId, workspaceId, turn, model);
            String reply = turn == null || turn.text() == null ? "" : turn.text();
            JarvisOralBench.Reading reading = JarvisOralBench.read(reply);
            readings.add(reading);
            personal.addAll(JarvisOralBench.personalFailures(s, reply, reading));
            Judgement judgement0 = judge(userId, apiKey, s, reading);
            JsonNode judgement = judgement0.note();
            cost = cost.add(judgement0.costEur());
            spent = spent.add(cost);
            last = cost;
            boolean isJudged = judgement != null;
            boolean first = isJudged && judgement.path("verdict_d_abord").asBoolean(false);
            judged += isJudged ? 1 : 0;
            verdictFirst += first ? 1 : 0;
            verdicts.add(new ScenarioVerdict(s.id(), s.category(), reading.oral(), reading.words(),
                    reading.invitation(), reading.genericInvitation(), reading.essentialLines(),
                    reading.asksConfirmation(), isJudged, first,
                    isJudged && judgement.path("chemin_parcouru").asBoolean(false),
                    isJudged ? cut(judgement.path("commentaire").asText(""), 200) : "non jugé",
                    cost.setScale(4, RoundingMode.HALF_UP)));
        }
        List<String> failures = new ArrayList<>(stops);
        failures.addAll(personal);
        failures.addAll(JarvisOralBench.gate(scenarios, readings, judged, verdictFirst));
        List<Integer> words = readings.stream().filter(r -> r.oral() != null).map(JarvisOralBench.Reading::words)
                .toList();
        return new Report(runId, "DONE", model, judgeModel, workspaceId, verdicts.size(),
                JarvisOralBench.percentile(words, 50), JarvisOralBench.percentile(words, 90), verdictFirst,
                (int) readings.stream().filter(r -> r.invitation() != null).count(),
                (int) readings.stream().filter(JarvisOralBench.Reading::genericInvitation).count(), failures.isEmpty(),
                failures, spent.setScale(4, RoundingMode.HALF_UP), capEur, verdicts, startedAt,
                OffsetDateTime.now(clock));
    }

    /** Un jugement : la note du juge ({@code null} sans réponse) et son coût en euros. */
    record Judgement(JsonNode note, BigDecimal costEur) {
    }

    /** Le juge : la ligne 🎙 donne-t-elle le verdict d'abord ? Raconte-t-elle le chemin ? */
    Judgement judge(UUID userId, String apiKey, JarvisOralBench.Scenario s, JarvisOralBench.Reading reading) {
        if (reading.oral() == null) {
            return new Judgement(null, BigDecimal.ZERO);
        }
        String exchange = "## Demande dite à la voix\n" + s.said() + "\n\n## Faits dont disposait l'assistant\n"
                + s.context() + "\n\n## Phrase dite à voix haute (ligne 🎙)\n« " + reading.oral() + " »";
        try {
            AgentTurn turn = provider.nextTurn(new AgentTurnRequest(judgeModel, JUDGE_SYSTEM,
                    List.of(AgentMessage.userText(exchange)), List.of(judgeTool()), apiKey, AgentReasoning.none(),
                    AgentContextPolicy.none(), AgentTurnMode.ACT, new AgentCallShape(600, false, true)));
            BigDecimal cost = account(userId, null, turn, judgeModel);
            return new Judgement(turn == null ? null : turn.toolCalls().stream()
                    .filter(tc -> JUDGE_TOOL.equals(tc.name())).map(AgentToolCall::input).findFirst().orElse(null),
                    cost);
        } catch (RuntimeException e) {
            return new Judgement(null, BigDecimal.ZERO);
        }
    }

    /** Inscrit l'appel au journal d'usage (annexe) et rend son coût en euros (0 si inconnu). Best-effort. */
    private BigDecimal account(UUID userId, UUID workspaceId, AgentTurn turn, String fallbackModel) {
        if (turn == null) {
            return BigDecimal.ZERO;
        }
        String served = turn.servedModel() != null ? turn.servedModel() : fallbackModel;
        try {
            if (ledger != null) {
                ledger.recordAnnex(userId, workspaceId, served, turn.inputTokens(), turn.outputTokens(),
                        turn.cacheReadTokens(), turn.cacheWriteTokens());
            }
            if (costs == null) {
                return BigDecimal.ZERO;
            }
            TurnCost cost = costs.calculate(new TurnTokens(Math.max(0, turn.inputTokens() - turn.cacheReadTokens()
                    - turn.cacheWriteTokens()), turn.outputTokens(), turn.cacheReadTokens(),
                    turn.cacheWriteTokens()), served);
            return cost == null || cost.amountUsd() == null ? BigDecimal.ZERO : cost.amountUsd().multiply(usdToEur);
        } catch (RuntimeException e) {
            log.warn("Banc de la parole : coût non inscrit ({})", e.getClass().getSimpleName());
            return BigDecimal.ZERO;
        }
    }

    private void save(UUID userId, Report report) {
        if (store == null) {
            return;
        }
        try {
            store.save(JarvisOralBenchRun.builder().id(report.runId()).userId(userId).startedAt(report.startedAt())
                    .status(report.status()).reportJson(JSON.writeValueAsString(report)).build());
            List<JarvisOralBenchRun> mine = store.findByUserIdOrderByStartedAtDesc(userId);
            if (mine.size() > MAX_STORED_RUNS) {
                store.deleteAll(mine.subList(MAX_STORED_RUNS, mine.size()));
            }
        } catch (Exception e) {
            log.warn("Banc de la parole : rapport non rangé en base ({})", e.getClass().getSimpleName());
        }
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    static final String JUDGE_SYSTEM = """
            Tu es un juge strict et impartial. Tu notes UNE phrase dite à voix haute par un assistant vocal \
            (« Jarvis ») en réponse à une demande, au vu des faits dont il disposait. Note en appelant l'outil \
            noter_parole une seule fois :
            - verdict_d_abord : vrai si la phrase donne d'abord la réponse ou le verdict (l'état, la cause, la \
            recommandation, ce qui est prêt, la confirmation demandée) — et non le contexte, la méthode ou une \
            formule d'attente ;
            - chemin_parcouru : vrai si la phrase raconte comment l'assistant a cherché (« j'ai regardé… », « après \
            vérification… », « en consultant… ») ;
            - commentaire : une phrase.""";

    static AgentTool judgeTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("verdict_d_abord", Map.of("type", "boolean"));
        props.put("chemin_parcouru", Map.of("type", "boolean"));
        props.put("commentaire", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.copyOf(props.keySet()));
        schema.put("additionalProperties", false);
        return new AgentTool(JUDGE_TOOL, "Rend la note de la phrase dite.", schema);
    }
}
