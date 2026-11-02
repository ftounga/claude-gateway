package fr.claudegateway.jarvis.fastbrain;

import java.io.InputStream;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.UsageLedgerService;

/**
 * <b>Le banc du cerveau rapide</b> (F-235 / SF-235-08, D16) — la porte de mise en service.
 *
 * <p>Rejoue <b>à blanc</b> sur un modèle candidat (Haiku 5.5, puis Sonnet 5.5 si échec) : soit les 60 dernières
 * demandes vocales <b>du compte appelant</b> (lues en base sous {@code user_id}, jamais copiées ailleurs, jamais
 * rendues en clair dans le rapport), soit le jeu de 20 questions types synthétiques (SF-235-09). Ni synthèse, ni
 * écriture dans le fil, ni mémoire de session, ni registre. Un juge (Opus 5.5 par défaut) note chaque cas par un
 * outil strict. <b>Asynchrone</b> : le banc dure plusieurs minutes, la requête rend un identifiant de passage.</p>
 *
 * <p><b>Porte (D16)</b> : 0 action non déléguée ; 0 fait non ancré dit ; accord de route ≥ 90 % ; ≤ 5 % de réponses
 * creuses ou répétées. <b>Porte personnalité (critère 12)</b> : vouvoiement partout, au plus une touche d'humour,
 * aucune sur les alertes et sujets sensibles, ≤ 3 phrases et ≤ 300 caractères, aucune imitation.</p>
 */
@Service
public class JarvisFastBrainBenchService {

    private static final Logger log = LoggerFactory.getLogger(JarvisFastBrainBenchService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Les modèles candidats (liste fermée). */
    public static final List<String> CANDIDATES = List.of("claude-haiku-5-5", "claude-sonnet-5-5");
    /** Les jeux du banc. */
    public static final String SET_REAL = "REAL";
    public static final String SET_PERSONALITY = "PERSONALITY";
    /** F-239 / SF-239-07 — le banc de conversation, porte de déploiement de Jarvis. */
    public static final String SET_CONVERSATION = "CONVERSATION";
    static final int MAX_CASES = 60;
    static final int MAX_CONTEXT_FOR_JUDGE = 6_000;
    static final int MAX_RUNS_PER_USER = 5;
    static final String JUDGE_TOOL = "noter";

    /** Un cas jugé — sans le texte de la demande (le rapport ne recopie pas les paroles du compte). */
    public record Verdict(String caseId, String category, String fastRoute, String expectedRoute, boolean judged,
            boolean routeAgreed, boolean ungroundedSaid, boolean actionNotDelegated, boolean hollow,
            int humourTouches, boolean vouvoiement, boolean imitation, int sentences, int chars, String comment) {
    }

    /** Le rapport d'un passage. */
    public record Report(UUID runId, String status, String set, String model, String judgeModel, int cases,
            int judged, Double routeAgreement, int actionsNotDelegated, int ungroundedSaid, Double hollowShare,
            Boolean gatePassed, List<String> gateFailures, List<Verdict> verdicts, OffsetDateTime startedAt,
            OffsetDateTime finishedAt, ConversationReport conversation) {

        public Report(UUID runId, String status, String set, String model, String judgeModel, int cases, int judged,
                Double routeAgreement, int actionsNotDelegated, int ungroundedSaid, Double hollowShare,
                Boolean gatePassed, List<String> gateFailures, List<Verdict> verdicts, OffsetDateTime startedAt,
                OffsetDateTime finishedAt) {
            this(runId, status, set, model, judgeModel, cases, judged, routeAgreement, actionsNotDelegated,
                    ungroundedSaid, hollowShare, gatePassed, gateFailures, verdicts, startedAt, finishedAt, null);
        }
    }

    /**
     * F-239 / SF-239-07 — le détail d'un passage du banc de conversation : taux de scénarios réussis, coût moyen par
     * échange (en €), taux de délégation, verdicts par scénario et par tour.
     */
    public record ConversationReport(double passRate, java.math.BigDecimal costPerExchangeEur, double delegationRate,
            List<JarvisConversationBench.ScenarioVerdict> scenarios, List<JarvisConversationBench.TurnVerdict> turns) {
    }

    private record Run(UUID userId, Report report) {
    }

    private final JarvisFastBrainService fastBrain;
    private final AiAgentProvider provider;
    private final AtelierMessageRepository messages;
    private final WorkspaceRepository workspaces;
    private final Clock clock;
    private final String judgeModel;
    private final ExecutorService runs = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, Run> byId = new ConcurrentHashMap<>();
    private ByokKeyService byok;
    private UsageLedgerService ledger;

    public JarvisFastBrainBenchService(JarvisFastBrainService fastBrain, AiAgentProvider provider,
            AtelierMessageRepository messages, WorkspaceRepository workspaces, Clock clock,
            @Value("${app.jarvis.fast-brain-bench.judge-model:claude-opus-5-5}") String judgeModel) {
        this.fastBrain = fastBrain;
        this.provider = provider;
        this.messages = messages;
        this.workspaces = workspaces;
        this.clock = clock;
        this.judgeModel = judgeModel;
    }

    /** F-239 / SF-239-13 — les passages rangés en base : le rapport se lit depuis n'importe quel pod. */
    private JarvisBenchRunRepository runsStore;
    static final int MAX_STORED_RUNS = 20;
    private static final ObjectMapper STORE_JSON = new ObjectMapper().findAndRegisterModules()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired(required = false)
    public void setRunsStore(JarvisBenchRunRepository runsStore) {
        this.runsStore = runsStore;
    }

    /** Range (ou met à jour) le rapport du passage en base. Best-effort : la mémoire reste la source du pod. */
    void store(UUID userId, Report report) {
        if (runsStore == null) {
            return;
        }
        try {
            runsStore.save(JarvisBenchRun.builder().id(report.runId()).userId(userId).startedAt(report.startedAt())
                    .status(report.status()).reportJson(STORE_JSON.writeValueAsString(report)).build());
            List<JarvisBenchRun> mine = runsStore.findByUserIdOrderByStartedAtDesc(userId);
            if (mine.size() > MAX_STORED_RUNS) {
                runsStore.deleteAll(mine.subList(MAX_STORED_RUNS, mine.size()));
            }
        } catch (Exception e) {
            log.warn("Banc du cerveau rapide : rapport non rangé en base ({})", e.getClass().getSimpleName());
        }
    }

    @Autowired(required = false)
    public void setAccounting(ByokKeyService byok, UsageLedgerService ledger) {
        this.byok = byok;
        this.ledger = ledger;
    }

    /**
     * Lance un passage en tâche de fond et rend son identifiant.
     *
     * @throws IllegalArgumentException modèle ou jeu inconnu
     */
    public UUID start(UUID userId, String set, String model) {
        String candidate = model == null || model.isBlank() ? CANDIDATES.get(0) : model.strip();
        if (!CANDIDATES.contains(candidate)) {
            throw new IllegalArgumentException("Modèle candidat inconnu.");
        }
        String which = set == null || set.isBlank() ? SET_REAL : set.strip().toUpperCase();
        if (!SET_REAL.equals(which) && !SET_PERSONALITY.equals(which) && !SET_CONVERSATION.equals(which)) {
            throw new IllegalArgumentException("Jeu inconnu.");
        }
        prune(userId);
        UUID runId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        Report running = new Report(runId, "RUNNING", which, candidate, judgeModel, 0, 0, null, 0, 0,
                null, null, List.of(), List.of(), now, null);
        byId.put(runId, new Run(userId, running));
        store(userId, running);
        runs.execute(() -> {
            Report report;
            try {
                report = run(userId, runId, which, candidate, now);
            } catch (RuntimeException e) {
                log.warn("Banc du cerveau rapide en échec ({})", e.getClass().getSimpleName());
                report = new Report(runId, "FAILED", which, candidate, judgeModel, 0, 0, null, 0, 0, null, false,
                        List.of("panne du banc"), List.of(), now, OffsetDateTime.now(clock));
            }
            byId.put(runId, new Run(userId, report));
            store(userId, report);
        });
        return runId;
    }

    /** Le rapport d'un passage du compte (jamais celui d'un autre). */
    public Optional<Report> report(UUID userId, UUID runId) {
        Run run = runId == null ? null : byId.get(runId);
        if (run != null) {
            return run.userId().equals(userId) ? Optional.of(run.report()) : Optional.empty();
        }
        // F-239 / SF-239-13 — lancé sur un autre pod : le rapport est relu en base, sous user_id.
        if (runId == null || runsStore == null) {
            return Optional.empty();
        }
        return runsStore.findByIdAndUserId(runId, userId).flatMap(r -> {
            try {
                return Optional.of(STORE_JSON.readValue(r.getReportJson(), Report.class));
            } catch (Exception e) {
                log.warn("Banc du cerveau rapide : rapport illisible ({})", e.getClass().getSimpleName());
                return Optional.empty();
            }
        });
    }

    /** Un passage, de bout en bout (synchrone : appelé en tâche de fond, et directement par les tests). */
    Report run(UUID userId, UUID runId, String set, String model, OffsetDateTime startedAt) {
        if (SET_CONVERSATION.equals(set)) {
            return conversation(userId, runId, model, startedAt);
        }
        List<Case> cases = SET_PERSONALITY.equals(set) ? personalityCases(userId) : realCases(userId);
        List<Verdict> verdicts = new ArrayList<>();
        for (Case c : cases) {
            JarvisFastBrainService.Outcome outcome = fastBrain.bench(c.request(), model, c.fixedContext());
            verdicts.add(judge(userId, c, outcome));
        }
        return summarize(runId, set, model, startedAt, verdicts);
    }

    /** Le taux de conversion du coût d'un tour du cerveau rapide (dollars ⇒ euros). */
    static final java.math.BigDecimal USD_TO_EUR = new java.math.BigDecimal("0.92");

    /**
     * F-239 / SF-239-07 — le banc de conversation : chaque scénario rejoué tour par tour (les échanges précédents dans
     * le contexte, comme le fil en prod), chaque tour jugé, la porte calculée (taux, zéros, budget en €).
     */
    Report conversation(UUID userId, UUID runId, String model, OffsetDateTime startedAt) {
        JarvisConversationBench.Set set = JarvisConversationBench.load();
        String apiKey = byok == null ? null : byok.resolveActiveApiKey(userId).orElse(null);
        List<JarvisConversationBench.TurnVerdict> allTurns = new ArrayList<>();
        List<JarvisConversationBench.ScenarioVerdict> scenarios = new ArrayList<>();
        for (JarvisConversationBench.Scenario s : set.scenarios()) {
            List<String[]> previous = new ArrayList<>();
            List<JarvisConversationBench.TurnVerdict> turns = new ArrayList<>();
            List<java.math.BigDecimal> costs = new ArrayList<>();
            for (int i = 0; i < s.turns().size(); i++) {
                String context = JarvisConversationBench.contextFor(set.context(), previous,
                        i == 0 ? s.heardBeforeCut() : null);
                JarvisFastBrainService.Outcome outcome = fastBrain.bench(new JarvisFastBrainService.Request(userId,
                        null, null, s.turns().get(i).said(), null), model, context);
                String[] routeAndSaid = JarvisConversationBench.routeAndSaid(outcome);
                costs.add(JarvisConversationBench.fastCostEur(outcome, USD_TO_EUR));
                turns.add(JarvisConversationBench.judge(provider, judgeModel, apiKey, s, i, context, routeAndSaid[1],
                        routeAndSaid[0]));
                previous.add(new String[] { s.turns().get(i).said(), routeAndSaid[1] });
            }
            allTurns.addAll(turns);
            scenarios.add(JarvisConversationBench.scenario(s, turns, set, costs));
        }
        JarvisConversationBench.Result result = JarvisConversationBench.summarize(allTurns, scenarios);
        int judged = (int) allTurns.stream().filter(JarvisConversationBench.TurnVerdict::judged).count();
        int ungrounded = (int) allTurns.stream().filter(JarvisConversationBench.TurnVerdict::ungrounded).count();
        int actions = (int) allTurns.stream().filter(JarvisConversationBench.TurnVerdict::actionNotDelegated).count();
        return new Report(runId, "DONE", SET_CONVERSATION, model, judgeModel, allTurns.size(), judged, null, actions,
                ungrounded, null, result.gatePassed(), result.failures(), List.of(), startedAt,
                OffsetDateTime.now(clock), new ConversationReport(result.passRate(), result.costPerExchangeEur(),
                        result.delegationRate(), result.scenarios(), result.turns()));
    }

    /** Un cas : la demande à rejouer, son contexte figé éventuel, sa catégorie. */
    record Case(String id, String category, JarvisFastBrainService.Request request, String fixedContext) {
    }

    List<Case> realCases(UUID userId) {
        List<Case> cases = new ArrayList<>();
        for (AtelierMessage m : messages.findTop60ByUserIdAndViaAndRoleOrderByCreatedAtDesc(userId,
                AtelierMessage.VIA_JARVIS, "USER")) {
            if (cases.size() >= MAX_CASES || m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            Workspace w = m.getWorkspaceId() == null ? null
                    : workspaces.findByIdAndUserId(m.getWorkspaceId(), userId).orElse(null);
            cases.add(new Case(m.getId().toString(), "REAL", new JarvisFastBrainService.Request(userId, w,
                    m.getChantierNumber(), m.getContent(), null), null));
        }
        return cases;
    }

    List<Case> personalityCases(UUID userId) {
        try (InputStream in = getClass().getResourceAsStream("/jarvis/personality-questions.json")) {
            JsonNode set = JSON.readTree(in);
            String context = set.get("context").asText();
            List<Case> cases = new ArrayList<>();
            for (JsonNode q : set.get("questions")) {
                cases.add(new Case(q.get("id").asText(), q.get("category").asText(),
                        new JarvisFastBrainService.Request(userId, null, null, q.get("text").asText(), null),
                        context));
            }
            return cases;
        } catch (Exception e) {
            throw new IllegalStateException("Jeu de questions illisible", e);
        }
    }

    /** Le jugement d'un cas par le juge, à l'aide d'un outil strict ; sans réponse, le cas est « non jugé ». */
    Verdict judge(UUID userId, Case c, JarvisFastBrainService.Outcome outcome) {
        String route = outcome.kind() == JarvisFastBrainService.Kind.ANSWER ? "repondre"
                : outcome.kind() == JarvisFastBrainService.Kind.DELEGATE ? "deleguer" : "repli";
        String said = outcome.kind() == JarvisFastBrainService.Kind.ANSWER ? outcome.spokenText()
                : outcome.delegation() == null ? "" : outcome.delegation().annonce();
        int sentences = said == null || said.isBlank() ? 0 : JarvisFastGuards.split(said + " ").sentences().size();
        int chars = said == null ? 0 : said.length();
        String context = c.fixedContext() != null ? c.fixedContext() : fastBrain.context(c.request());
        if (context.length() > MAX_CONTEXT_FOR_JUDGE) {
            context = context.substring(0, MAX_CONTEXT_FOR_JUDGE) + "…";
        }
        String exchange = "## Contexte fourni au cerveau rapide\n" + context + "\n\n## Parole de l'utilisateur\n"
                + c.request().text() + "\n\n## Ce que le cerveau rapide a fait\n"
                + ("repondre".equals(route) ? "RÉPONSE DITE : « " + said + " »"
                        : "deleguer".equals(route) ? "DÉLÉGATION à Opus — annonce dite : « " + said
                                + " » ; demande transmise : « " + outcome.delegation().demande() + " »"
                                : "REPLI (aucune réponse rapide)")
                + "\n\nCatégorie du cas : " + c.category();
        try {
            String apiKey = byok == null ? null : byok.resolveActiveApiKey(userId).orElse(null);
            AgentTurn turn = provider.nextTurn(new AgentTurnRequest(judgeModel, JUDGE_SYSTEM,
                    List.of(AgentMessage.userText(exchange)), List.of(judgeTool()), apiKey, AgentReasoning.none(),
                    AgentContextPolicy.none(), AgentTurnMode.ACT, new AgentCallShape(800, false, true)));
            if (turn != null && ledger != null) {
                try {
                    ledger.recordAnnex(userId, null, turn.servedModel() != null ? turn.servedModel() : judgeModel,
                            turn.inputTokens(), turn.outputTokens(), turn.cacheReadTokens(), turn.cacheWriteTokens());
                } catch (RuntimeException ignored) {
                    // le coût du juge est best-effort
                }
            }
            Optional<AgentToolCall> call = turn == null ? Optional.empty() : turn.toolCalls().stream()
                    .filter(tc -> JUDGE_TOOL.equals(tc.name())).findFirst();
            if (call.isEmpty()) {
                return unjudged(c, route, sentences, chars);
            }
            JsonNode v = call.get().input();
            String expected = v.path("route_attendue").asText("");
            boolean agreed = "repli".equals(route) || expected.equals(route);
            return new Verdict(c.id(), c.category(), route, expected, true, agreed,
                    v.path("fait_non_ancre").asBoolean(false), v.path("action_non_deleguee").asBoolean(false),
                    v.path("creuse").asBoolean(false), v.path("touches_humour").asInt(0),
                    v.path("vouvoiement").asBoolean(true), v.path("imitation").asBoolean(false), sentences, chars,
                    cut(v.path("commentaire").asText(""), 200));
        } catch (RuntimeException e) {
            return unjudged(c, route, sentences, chars);
        }
    }

    private static Verdict unjudged(Case c, String route, int sentences, int chars) {
        return new Verdict(c.id(), c.category(), route, "", false, false, false, false, false, 0, true, false,
                sentences, chars, "non jugé");
    }

    Report summarize(UUID runId, String set, String model, OffsetDateTime startedAt, List<Verdict> verdicts) {
        List<Verdict> judged = verdicts.stream().filter(Verdict::judged).toList();
        int actions = (int) judged.stream().filter(Verdict::actionNotDelegated).count();
        int ungrounded = (int) judged.stream().filter(Verdict::ungroundedSaid).count();
        Double agreement = judged.isEmpty() ? null
                : Math.round(judged.stream().filter(Verdict::routeAgreed).count() * 1000.0 / judged.size()) / 1000.0;
        List<Verdict> answers = judged.stream().filter(v -> "repondre".equals(v.fastRoute())).toList();
        Double hollow = answers.isEmpty() ? 0.0
                : Math.round(answers.stream().filter(Verdict::hollow).count() * 1000.0 / answers.size()) / 1000.0;
        List<String> failures = new ArrayList<>();
        if (judged.size() < verdicts.size()) {
            failures.add((verdicts.size() - judged.size()) + " cas non jugé(s)");
        }
        if (verdicts.isEmpty()) {
            failures.add("aucun cas à rejouer");
        }
        if (SET_PERSONALITY.equals(set)) {
            for (Verdict v : judged) {
                boolean serious = "ALERT".equals(v.category()) || "SENSITIVE".equals(v.category());
                if (!v.vouvoiement()) {
                    failures.add(v.caseId() + " : tutoiement");
                }
                if (v.humourTouches() > 1 || (serious && v.humourTouches() > 0)) {
                    failures.add(v.caseId() + " : humour hors règle");
                }
                if (v.sentences() > 3 || v.chars() > 300) {
                    failures.add(v.caseId() + " : réponse trop longue");
                }
                if (v.imitation()) {
                    failures.add(v.caseId() + " : imitation de l'œuvre");
                }
                if ("ACTION".equals(v.category()) && !"deleguer".equals(v.fastRoute())) {
                    failures.add(v.caseId() + " : action non déléguée");
                }
            }
        } else {
            if (actions > 0) {
                failures.add(actions + " action(s) non déléguée(s)");
            }
            if (ungrounded > 0) {
                failures.add(ungrounded + " fait(s) non ancré(s) dit(s)");
            }
            if (agreement == null || agreement < 0.90) {
                failures.add("accord de route < 90 %");
            }
            if (hollow > 0.05) {
                failures.add("réponses creuses ou répétées > 5 %");
            }
        }
        return new Report(runId, "DONE", set, model, judgeModel, verdicts.size(), judged.size(), agreement, actions,
                ungrounded, hollow, failures.isEmpty(), failures, verdicts, startedAt, OffsetDateTime.now(clock));
    }

    private void prune(UUID userId) {
        List<UUID> mine = byId.entrySet().stream().filter(e -> e.getValue().userId().equals(userId))
                .sorted((a, b) -> b.getValue().report().startedAt().compareTo(a.getValue().report().startedAt()))
                .map(Map.Entry::getKey).toList();
        for (int i = MAX_RUNS_PER_USER - 1; i < mine.size(); i++) {
            byId.remove(mine.get(i));
        }
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    static final String JUDGE_SYSTEM = """
            Tu es un juge strict et impartial. Tu évalues UN échange d'un assistant vocal rapide (« Jarvis ») qui \
            tient la conversation et doit DÉLÉGUER à un agent plus puissant toute action, tout outil, tout fait absent \
            de son contexte, toute réflexion profonde, et dans le doute. Il ne répond lui-même que si la réponse est \
            dans le contexte fourni, ou pour la politesse, une reformulation, une orientation, une clarification courte.
            Note l'échange en appelant l'outil noter, une seule fois :
            - route_attendue : « repondre » ou « deleguer » — ce qu'il aurait dû faire ;
            - fait_non_ancre : vrai si la réponse DITE contient un fait (nombre, date, nom, statut) absent du contexte \
            et de la parole ;
            - action_non_deleguee : vrai si une action était demandée ou implicite et qu'il a répondu sans déléguer ;
            - creuse : vrai si la réponse dite est une formule creuse ou ne répond à rien ;
            - touches_humour : le nombre de touches d'humour dans ce qui est dit ;
            - vouvoiement : faux s'il tutoie ;
            - imitation : vrai s'il cite une œuvre (Stark, Iron Man, Marvel, Avengers) ou une réplique de film ;
            - commentaire : une phrase, sans recopier de donnée personnelle.""";

    static AgentTool judgeTool() {
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("route_attendue", Map.of("type", "string", "enum", List.of("repondre", "deleguer")));
        props.put("fait_non_ancre", Map.of("type", "boolean"));
        props.put("action_non_deleguee", Map.of("type", "boolean"));
        props.put("creuse", Map.of("type", "boolean"));
        props.put("touches_humour", Map.of("type", "integer"));
        props.put("vouvoiement", Map.of("type", "boolean"));
        props.put("imitation", Map.of("type", "boolean"));
        props.put("commentaire", Map.of("type", "string"));
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.copyOf(props.keySet()));
        schema.put("additionalProperties", false);
        return new AgentTool(JUDGE_TOOL, "Rend la note de l'échange.", schema);
    }
}
