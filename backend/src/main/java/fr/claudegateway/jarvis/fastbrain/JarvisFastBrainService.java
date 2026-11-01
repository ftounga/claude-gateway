package fr.claudegateway.jarvis.fastbrain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.agent.AgentCallShape;
import fr.claudegateway.agent.AgentContextPolicy;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentReasoning;
import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnMode;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.quota.ProviderCostCalculator;
import fr.claudegateway.quota.ProviderPricingProperties;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.quota.TurnCost;
import fr.claudegateway.quota.TurnTokens;
import fr.claudegateway.quota.UsageLedgerService;

/**
 * <b>Le cerveau rapide de Jarvis</b> (F-235 / SF-235-01) : tient la conversation vocale et, dès qu'il faut agir,
 * chercher ou réfléchir, <b>délègue</b> au tour Opus du terminal — inchangé.
 *
 * <p><b>Provider Independence</b> : l'appel passe par {@link AiAgentProvider} ; le modèle est un réglage. Le
 * raisonnement est <b>éteint</b> ({@link AgentReasoning#off(String)}) et la forme d'appel est <b>compacte</b>
 * ({@link AgentCallShape#compact(int)} : plafond court, aucun outil serveur, outil strict).</p>
 *
 * <p><b>Jamais muet, jamais inventé</b> (D9b) : refus, erreur, délai sans premier jeton, réponse vide ⇒ délégation
 * <b>automatique</b> de la parole d'origine. Plafond du mois atteint ou quota du compte épuisé ⇒ <b>repli</b> : l'écran
 * fait ce qu'il faisait avant (tout à Opus), sans erreur à l'oral (D15).</p>
 *
 * <p><b>Coût</b> : chaque appel est inscrit au journal d'usage (appel annexe, au tarif de son modèle) et dans la
 * journée Jarvis du compte, qui porte le plafond propre. <b>Aucun contenu journalisé</b>.</p>
 */
@Service
public class JarvisFastBrainService {

    private static final Logger log = LoggerFactory.getLogger(JarvisFastBrainService.class);
    static final BigDecimal DEFAULT_USD_TO_EUR = new BigDecimal("0.92");
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm",
            Locale.FRENCH);

    /** Ce que l'écran demande. */
    public record Request(UUID userId, Workspace workspace, Integer chantier, String text, String heardBeforeCut) {
    }

    /** Ce que le cerveau rapide a décidé. */
    public enum Kind {
        /** Il a répondu lui-même. */
        ANSWER,
        /** Il a délégué (lui-même, ou d'office). */
        DELEGATE,
        /** Il s'efface : l'écran garde le mode d'avant (plafond, quota). */
        FALLBACK
    }

    /** Une délégation : ce qu'Opus doit faire, et ce que Jarvis dit en la lançant. */
    public record Delegation(UUID delegationId, String demande, String label, String annonce, UUID subjectId,
            Integer chantier, boolean automatic, String reason) {
    }

    /** Le résultat d'un échange. */
    public record Outcome(Kind kind, String spokenText, Delegation delegation, String reason, String model,
            long firstTokenMs, BigDecimal costUsd, Guards guards) {

        public Outcome(Kind kind, String spokenText, Delegation delegation, String reason, String model,
                long firstTokenMs, BigDecimal costUsd) {
            this(kind, spokenText, delegation, reason, model, firstTokenMs, costUsd, Guards.NONE);
        }
    }

    /**
     * Ce que les garde-fous ont fait pendant l'échange (F-235 / SF-235-03, D9, D11) — compté, jamais de contenu ;
     * consommé par la mesure (SF-235-07).
     */
    public record Guards(int groundingTrips, int fillersRemoved, int repeatsRemoved, boolean forcedDelegation,
            boolean toolText) {
        public static final Guards NONE = new Guards(0, 0, 0, false, false);
    }

    /** Ce qui reçoit les fragments de la réponse au fil de l'eau. */
    @FunctionalInterface
    public interface Sink {
        void text(String delta);
    }

    /** Raisons d'une délégation d'office ou d'un repli (comptées, jamais dites). */
    public static final String REASON_REFUSAL = "refusal";
    public static final String REASON_ERROR = "error";
    public static final String REASON_TIMEOUT = "timeout";
    public static final String REASON_EMPTY = "empty";
    public static final String REASON_BUDGET = "budget";
    public static final String REASON_QUOTA = "quota";
    /** SF-235-03 : un fait non ancré (D9a), un texte d'outil (D9c), une délégation demandée par le PO (D9d). */
    public static final String REASON_GROUNDING = "grounding";
    public static final String REASON_TOOL_TEXT = "tool_text";
    public static final String REASON_FORCED = "forced";
    /** Nombre de réponses précédentes de la session dont les phrases ne doivent pas revenir (D11c). */
    static final int REPEAT_WINDOW = 5;

    /** L'annonce d'une délégation d'office : courte, vraie, jamais un fait. */
    static final String AUTO_ANNOUNCE = "Je vérifie, Monsieur.";

    private final AiAgentProvider provider;
    private final JarvisFastBrainProperties properties;
    private final JarvisUsageService usage;
    private final WorkspaceRepository workspaces;
    private final Clock clock;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    private ByokKeyService byok;
    private UsageLedgerService ledger;
    private ProviderCostCalculator costs;
    private QuotaService quota;
    private BigDecimal usdToEur = DEFAULT_USD_TO_EUR;
    private JarvisFastContextBuilder contextBuilder;
    private JarvisFastMemory memory;
    private JarvisDelegationRegistry delegations;
    /** Dernier pré-chauffage par compte (D6) : au plus un toutes les {@link #WARM_EVERY}. */
    private final java.util.Map<UUID, java.time.Instant> warmed = new java.util.concurrent.ConcurrentHashMap<>();
    static final java.time.Duration WARM_EVERY = java.time.Duration.ofMinutes(4);
    static final String WARM_TEXT = "[Pré-chauffage — ne réponds rien]";

    public JarvisFastBrainService(AiAgentProvider provider, JarvisFastBrainProperties properties,
            JarvisUsageService usage, WorkspaceRepository workspaces, Clock clock) {
        this.provider = provider;
        this.properties = properties == null ? JarvisFastBrainProperties.defaults() : properties;
        this.usage = usage;
        this.workspaces = workspaces;
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setAccounting(ByokKeyService byok, UsageLedgerService ledger, ProviderCostCalculator costs,
            QuotaService quota) {
        this.byok = byok;
        this.ledger = ledger;
        this.costs = costs;
        this.quota = quota;
    }

    /** F-235 / SF-235-02 — le contexte compact, la mémoire de session, les délégations en cours. */
    @Autowired(required = false)
    public void setContext(JarvisFastContextBuilder contextBuilder, JarvisFastMemory memory,
            JarvisDelegationRegistry delegations) {
        this.contextBuilder = contextBuilder;
        this.memory = memory;
        this.delegations = delegations;
    }

    @Autowired(required = false)
    public void setPricing(ProviderPricingProperties pricing) {
        if (pricing != null && pricing.usdToEur() != null) {
            this.usdToEur = pricing.usdToEur();
        }
    }

    /** Vrai si le cerveau rapide est allumé (coupe-circuit D17). */
    public boolean enabled() {
        return properties.isEnabled();
    }

    /** Le modèle rapide configuré. */
    public String model() {
        return properties.model();
    }

    /**
     * Un échange : la parole du PO, le contexte, la décision. Ne lève jamais : toute panne devient une délégation
     * d'office ou un repli.
     */
    public Outcome converse(Request request, Sink sink) {
        Outcome outcome = decide(request, sink);
        // SF-235-02 : la mémoire de session (mots seuls) et le registre des délégations en cours.
        if (memory != null && outcome.kind() != Kind.FALLBACK) {
            memory.remember(request.userId(), request.text(), outcome.kind() == Kind.ANSWER ? outcome.spokenText()
                    : outcome.delegation().annonce());
        }
        if (delegations != null && outcome.kind() == Kind.DELEGATE) {
            Delegation d = outcome.delegation();
            String name = request.workspace() != null && request.workspace().getId().equals(d.subjectId())
                    ? request.workspace().getName()
                    : workspaces.findByIdAndUserId(d.subjectId(), request.userId()).map(Workspace::getName).orElse("");
            delegations.started(request.userId(), d.delegationId(), d.subjectId(), name, d.chantier(),
                    d.label() == null || d.label().isBlank() ? d.demande() : d.label());
        }
        return outcome;
    }

    /**
     * Pré-chauffage du cache (D6) : un appel {@code max_tokens: 0} portant la consigne et l'outil, au plus toutes les
     * 4 min par compte. Rend vrai s'il est parti. Ne lève jamais.
     */
    /** Le pré-chauffage en tâche de fond : la requête HTTP n'attend jamais le modèle. */
    public void warmAsync(UUID userId, Workspace workspace) {
        calls.execute(() -> warm(userId, workspace));
    }

    public boolean warm(UUID userId, Workspace workspace) {
        java.time.Instant now = clock.instant();
        java.time.Instant last = warmed.get(userId);
        if (last != null && last.plus(WARM_EVERY).isAfter(now)) {
            return false;
        }
        if (overBudget(userId)) {
            return false;
        }
        warmed.put(userId, now);
        try {
            String apiKey = byok == null ? null : byok.resolveActiveApiKey(userId).orElse(null);
            AgentTurn turn = provider.nextTurn(new AgentTurnRequest(properties.model(), JarvisFastBrainPrompt.system(),
                    List.of(AgentMessage.userText(WARM_TEXT)), List.of(JarvisFastBrainPrompt.delegateTool()), apiKey,
                    AgentReasoning.off(properties.effort()), AgentContextPolicy.none(), AgentTurnMode.ACT,
                    AgentCallShape.compact(0)));
            if (turn != null) {
                account(userId, workspace, turn);
            }
            return true;
        } catch (RuntimeException e) {
            log.debug("Cerveau rapide : pré-chauffage en échec ({})", e.getClass().getSimpleName());
            return true;
        }
    }

    Outcome decide(Request request, Sink sink) {
        Sink out = sink == null ? delta -> { } : sink;
        UUID userId = request.userId();
        // D9d : « vérifie », « demande à Opus », « c'est faux »… ⇒ Opus, sans appel au cerveau rapide.
        if (JarvisFastGuards.forcesDelegation(request.text())) {
            return forced(request);
        }
        if (overBudget(userId)) {
            return fallback(REASON_BUDGET);
        }
        if (quota != null) {
            try {
                quota.assertWithinQuota(userId);
            } catch (RuntimeException e) {
                return fallback(REASON_QUOTA);
            }
        }
        String apiKey = byok == null ? null : byok.resolveActiveApiKey(userId).orElse(null);
        String context = context(request);
        SentenceGate gate = new SentenceGate(context, request.text(), recentSentences(userId), out);
        AgentTurnRequest call = new AgentTurnRequest(properties.model(), JarvisFastBrainPrompt.system(),
                messages(request, context), List.of(JarvisFastBrainPrompt.delegateTool()), apiKey,
                AgentReasoning.off(properties.effort()), AgentContextPolicy.none(), AgentTurnMode.ACT,
                AgentCallShape.compact(properties.maxTokens()));

        long started = System.nanoTime();
        AtomicLong firstToken = new AtomicLong(-1L);
        CountDownLatch first = new CountDownLatch(1);
        Object lock = new Object();
        boolean[] abandoned = { false };
        CompletableFuture<AgentTurn> pending = CompletableFuture.supplyAsync(() -> provider.nextTurn(call, delta -> {
            if (firstToken.compareAndSet(-1L, (System.nanoTime() - started) / 1_000_000L)) {
                first.countDown();
            }
            synchronized (lock) {
                if (!abandoned[0]) {
                    gate.accept(delta);
                }
            }
        }), calls);
        pending.whenComplete((turn, failure) -> {
            first.countDown();
            if (turn != null) {
                account(userId, request.workspace(), turn);
            }
        });

        AgentTurn turn;
        try {
            boolean started1 = first.await(properties.firstTokenTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!started1 && !pending.isDone()) {
                synchronized (lock) {
                    abandoned[0] = true;
                }
                return auto(request, REASON_TIMEOUT, -1L);
            }
            turn = pending.get(Math.max(1L, properties.firstTokenTimeout().toMillis()) * 20L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return auto(request, REASON_ERROR, firstToken.get());
        } catch (Exception e) {
            log.warn("Cerveau rapide : appel en échec ({})", e.getClass().getSimpleName());
            return auto(request, REASON_ERROR, firstToken.get());
        }
        long firstMs = firstToken.get() >= 0 ? firstToken.get() : (System.nanoTime() - started) / 1_000_000L;
        if (turn == null || turn.refused()) {
            return auto(request, REASON_REFUSAL, firstMs, gate.guards());
        }
        Optional<AgentToolCall> delegate = turn.toolCalls().stream()
                .filter(tc -> JarvisFastBrainPrompt.TOOL_NAME.equals(tc.name())).findFirst();
        synchronized (lock) {
            if (delegate.isEmpty() && !gate.streamedAnything() && turn.text() != null) {
                gate.accept(turn.text()); // fournisseur sans flux : le texte entier passe la même porte
            }
            gate.finish();
        }
        if (gate.abortReason() != null) {
            return auto(request, gate.abortReason(), firstMs, gate.guards());
        }
        if (delegate.isPresent()) {
            Delegation d = withSafeAnnounce(delegation(request, delegate.get().input()), gate);
            return new Outcome(Kind.DELEGATE, gate.spoken(), d, null, properties.model(), firstMs, null,
                    gate.guards());
        }
        String text = gate.spoken();
        if (text.isEmpty() || turn.truncated()) {
            return auto(request, REASON_EMPTY, firstMs, gate.guards());
        }
        return new Outcome(Kind.ANSWER, text, null, null, properties.model(), firstMs, null, gate.guards());
    }

    /**
     * Délégation demandée par le PO (D9d) : la parole <b>précédente</b> de la session part à Opus (à défaut, la
     * parole elle-même), sans appel au cerveau rapide.
     */
    Outcome forced(Request request) {
        String previous = null;
        if (memory != null) {
            List<JarvisFastMemory.Exchange> recalled = memory.recall(request.userId());
            if (!recalled.isEmpty()) {
                previous = recalled.get(recalled.size() - 1).said();
            }
        }
        String said = request.text() == null ? "" : request.text().strip();
        String demande = previous == null || previous.isBlank() ? said
                : previous + "\n(L'utilisateur demande de vérifier : « " + said + " ». La réponse rapide précédente "
                        + "n'était pas vérifiée.)";
        UUID subject = request.workspace() == null ? null : request.workspace().getId();
        return new Outcome(Kind.DELEGATE, "", new Delegation(UUID.randomUUID(), demande, "", AUTO_ANNOUNCE, subject,
                request.chantier(), true, REASON_FORCED), REASON_FORCED, properties.model(), -1L, null,
                new Guards(0, 0, 0, true, false));
    }

    /**
     * L'annonce d'une délégation (D11b) : creuse, répétée ou non ancrée ⇒ remplacée par une annonce qui nomme
     * l'objet (« Je regarde : tickets bloqués d'Edenred. »).
     */
    Delegation withSafeAnnounce(Delegation d, SentenceGate gate) {
        String annonce = d.annonce();
        boolean bad = annonce == null || annonce.isBlank() || AUTO_ANNOUNCE.equals(annonce)
                || JarvisFastGuards.isFillerAlone(annonce) || gate.repeats(annonce)
                || JarvisFastGuards.looksLikeToolCall(annonce) || gate.ungrounded(annonce) != null;
        if (!bad) {
            return d;
        }
        String label = d.label() == null ? "" : d.label().strip().replaceAll("[.!?…]+$", "");
        String safe = label.isEmpty() || JarvisFastGuards.looksLikeToolCall(label) || gate.ungrounded(label) != null
                ? AUTO_ANNOUNCE
                : "Je regarde : " + label + ".";
        if (!AUTO_ANNOUNCE.equals(safe) && gate.repeats(safe)) {
            safe = "Je lance la recherche : " + label + ".";
        }
        return new Delegation(d.delegationId(), d.demande(), d.label(), safe, d.subjectId(), d.chantier(),
                d.automatic(), d.reason());
    }

    /** Les phrases des {@value #REPEAT_WINDOW} dernières réponses de la session (D11c). */
    List<String> recentSentences(UUID userId) {
        if (memory == null) {
            return List.of();
        }
        List<JarvisFastMemory.Exchange> recalled = memory.recall(userId);
        List<String> sentences = new ArrayList<>();
        for (int i = Math.max(0, recalled.size() - REPEAT_WINDOW); i < recalled.size(); i++) {
            String answered = recalled.get(i).answered();
            if (answered == null || answered.isBlank()) {
                continue;
            }
            JarvisFastGuards.Split split = JarvisFastGuards.split(answered);
            sentences.addAll(split.sentences());
            if (!split.rest().isBlank()) {
                sentences.add(split.rest().strip());
            }
        }
        return sentences;
    }

    /**
     * <b>La porte des phrases</b> (D9, D11) : retient les fragments jusqu'à la fin d'une phrase, la contrôle, puis
     * seulement la laisse partir vers l'écran. Un fait non ancré ou un texte d'outil ferme la porte (plus rien ne
     * part) et l'échange devient une délégation d'office.
     */
    static final class SentenceGate {
        private static final java.util.regex.Pattern MONSIEUR = java.util.regex.Pattern
                .compile("(?i)(,\\s*)?\\bmonsieur\\b");
        private final String context;
        private final String said;
        private final List<String> recent;
        private final Sink out;
        private final StringBuilder pending = new StringBuilder();
        private final StringBuilder spoken = new StringBuilder();
        private final List<String> saidNow = new ArrayList<>();
        private boolean streamed;
        private boolean addressed;
        private String abortReason;
        private int groundingTrips;
        private int fillersRemoved;
        private int repeatsRemoved;
        private boolean toolText;

        SentenceGate(String context, String said, List<String> recent, Sink out) {
            this.context = context == null ? "" : context;
            this.said = said == null ? "" : said;
            this.recent = recent == null ? List.of() : recent;
            this.out = out;
        }

        void accept(String delta) {
            if (delta == null || delta.isEmpty() || abortReason != null) {
                return;
            }
            streamed = true;
            pending.append(delta);
            JarvisFastGuards.Split split = JarvisFastGuards.split(pending.toString());
            pending.setLength(0);
            pending.append(split.rest());
            for (String sentence : split.sentences()) {
                check(sentence);
            }
            // Un appel d'outil écrit en texte se voit souvent avant la fin de « phrase » : on coupe tout de suite.
            if (abortReason == null && JarvisFastGuards.looksLikeToolCall(pending.toString())) {
                abort(REASON_TOOL_TEXT);
            }
        }

        void finish() {
            String rest = pending.toString().strip();
            pending.setLength(0);
            if (!rest.isEmpty()) {
                check(rest);
            }
        }

        private void check(String raw) {
            if (abortReason != null) {
                return;
            }
            String sentence = raw.strip();
            if (sentence.isEmpty()) {
                return;
            }
            try {
                if (JarvisFastGuards.looksLikeToolCall(sentence)) {
                    abort(REASON_TOOL_TEXT);
                    return;
                }
                if (ungrounded(sentence) != null) {
                    groundingTrips++;
                    abort(REASON_GROUNDING);
                    return;
                }
                if (JarvisFastGuards.isFillerAlone(sentence)) {
                    fillersRemoved++;
                    return;
                }
                if (repeats(sentence)) {
                    repeatsRemoved++;
                    return;
                }
            } catch (RuntimeException e) {
                groundingTrips++;
                abort(REASON_GROUNDING); // une garde en panne ne laisse jamais passer un fait
                return;
            }
            String kept = oneMonsieur(sentence);
            saidNow.add(kept);
            if (spoken.length() > 0) {
                spoken.append(' ');
            }
            spoken.append(kept);
            out.text(kept + " ");
        }

        /** « Monsieur » au plus une fois par réponse (D11e) : les suivants sont retirés. */
        private String oneMonsieur(String sentence) {
            java.util.regex.Matcher m = MONSIEUR.matcher(sentence);
            StringBuilder b = new StringBuilder();
            while (m.find()) {
                if (!addressed) {
                    addressed = true;
                    m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(m.group()));
                } else {
                    m.appendReplacement(b, "");
                }
            }
            m.appendTail(b);
            String cleaned = b.toString().replaceAll("\\s+([.!?…,])", "$1").replaceAll("\\s{2,}", " ").strip();
            return cleaned.isEmpty() ? sentence : cleaned;
        }

        String ungrounded(String sentence) {
            return JarvisFastGuards.ungroundedFact(sentence, context, said);
        }

        boolean repeats(String sentence) {
            return JarvisFastGuards.isRepeat(sentence, recent) || JarvisFastGuards.isRepeat(sentence, saidNow);
        }

        private void abort(String reason) {
            if (REASON_TOOL_TEXT.equals(reason)) {
                toolText = true;
            }
            abortReason = reason;
            pending.setLength(0);
        }

        boolean streamedAnything() {
            return streamed;
        }

        String abortReason() {
            return abortReason;
        }

        String spoken() {
            return spoken.toString().strip();
        }

        Guards guards() {
            return new Guards(groundingTrips, fillersRemoved, repeatsRemoved, false, toolText);
        }
    }


    /** Les messages de l'appel : la parole et son contexte, dans le message (jamais dans la consigne système). */
    List<AgentMessage> messages(Request request) {
        return messages(request, context(request));
    }

    /**
     * Les échanges de la session (mots seuls, D6) puis le dernier message, seul à porter le contexte. Un échange
     * dont la réponse est vide n'est pas rejoué (la conversation doit alterner).
     */
    List<AgentMessage> messages(Request request, String context) {
        List<AgentMessage> messages = new ArrayList<>();
        if (memory != null) {
            for (JarvisFastMemory.Exchange e : memory.recall(request.userId())) {
                if (e.answered() == null || e.answered().isBlank()) {
                    continue;
                }
                messages.add(AgentMessage.userText(e.said()));
                messages.add(AgentMessage.assistant(List.of(new fr.claudegateway.agent.AgentContentBlock.Text(
                        e.answered()))));
            }
        }
        messages.add(AgentMessage.userText(message(context, request.text())));
        return messages;
    }

    /** Le message d'un échange : le contexte, puis la parole. */
    static String message(String context, String text) {
        return "[Contexte]\n" + (context == null ? "" : context.strip()) + "\n\n[Parole de l'utilisateur]\n"
                + (text == null ? "" : text.strip());
    }

    /** Le contexte de l'échange (SF-235-02) ; à défaut de constructeur, l'heure, le sujet et le chantier courants. */
    String context(Request request) {
        if (contextBuilder != null) {
            return contextBuilder.build(request.userId(), request.workspace(), request.chantier(),
                    request.heardBeforeCut());
        }
        StringBuilder context = new StringBuilder();
        context.append("Maintenant : ").append(ZonedDateTime.now(clock).withZoneSameInstant(ZONE).format(STAMP))
                .append('\n');
        Workspace workspace = request.workspace();
        if (workspace != null) {
            context.append("Sujet courant : ").append(workspace.getName()).append(" (id ").append(workspace.getId())
                    .append(")\n");
        }
        if (request.chantier() != null) {
            context.append("Chantier courant : ").append(request.chantier()).append('\n');
        }
        return context.toString();
    }

    /** La délégation décidée par le modèle ; sujet cible vérifié sous {@code user_id}, sinon le sujet courant. */
    Delegation delegation(Request request, JsonNode input) {
        String demande = text(input, "demande");
        String label = text(input, "label");
        String annonce = text(input, "annonce");
        UUID subject = request.workspace() == null ? null : request.workspace().getId();
        Integer chantier = request.chantier();
        String asked = text(input, "sujet");
        if (!asked.isEmpty()) {
            try {
                UUID candidate = UUID.fromString(asked);
                if (workspaces.findByIdAndUserId(candidate, request.userId()).isPresent()) {
                    if (!candidate.equals(subject)) {
                        chantier = null;
                    }
                    subject = candidate;
                }
            } catch (IllegalArgumentException ignored) {
                // Identifiant illisible : le sujet courant reste la cible.
            }
        }
        JsonNode site = input == null ? null : input.get("chantier");
        if (site != null && site.isIntegralNumber()) {
            chantier = site.asInt();
        }
        if (demande.isEmpty()) {
            demande = request.text() == null ? "" : request.text().strip();
        }
        if (annonce.isEmpty()) {
            annonce = AUTO_ANNOUNCE;
        }
        return new Delegation(UUID.randomUUID(), demande, label, annonce, subject, chantier, false, null);
    }

    /** Délégation d'office de la parole d'origine (D9b) : jamais de silence, jamais d'invention. */
    Outcome auto(Request request, String reason, long firstMs) {
        return auto(request, reason, firstMs, Guards.NONE);
    }

    Outcome auto(Request request, String reason, long firstMs, Guards guards) {
        UUID subject = request.workspace() == null ? null : request.workspace().getId();
        String said = request.text() == null ? "" : request.text().strip();
        return new Outcome(Kind.DELEGATE, "", new Delegation(UUID.randomUUID(), said, "", AUTO_ANNOUNCE, subject,
                request.chantier(), true, reason), reason, properties.model(), firstMs, null, guards);
    }

    private Outcome fallback(String reason) {
        return new Outcome(Kind.FALLBACK, "", null, reason, properties.model(), -1L, null);
    }

    /** Vrai si le plafond mensuel propre du cerveau rapide est atteint (D15). */
    boolean overBudget(UUID userId) {
        BigDecimal budgetEur = properties.monthlyBudgetEur();
        if (budgetEur.signum() == 0) {
            return true;
        }
        try {
            BigDecimal spentEur = usage.monthFastBrainCostUsd(userId).multiply(usdToEur);
            return spentEur.compareTo(budgetEur) >= 0;
        } catch (RuntimeException e) {
            return false; // illisible : le quota du compte reste la garde
        }
    }

    /** Coût de l'appel : journal d'usage (annexe) et journée Jarvis. Best-effort. */
    void account(UUID userId, Workspace workspace, AgentTurn turn) {
        try {
            String model = turn.servedModel() != null ? turn.servedModel() : properties.model();
            if (ledger != null) {
                ledger.recordAnnex(userId, workspace == null ? null : workspace.getId(), model, turn.inputTokens(),
                        turn.outputTokens(), turn.cacheReadTokens(), turn.cacheWriteTokens());
            }
            BigDecimal usd = BigDecimal.ZERO;
            if (costs != null) {
                TurnCost cost = costs.calculate(new TurnTokens(turn.fullPriceInputTokens(), turn.outputTokens(),
                        turn.cacheReadTokens(), turn.cacheWriteTokens()), model);
                usd = cost == null || cost.amountUsd() == null ? BigDecimal.ZERO : cost.amountUsd();
            }
            usage.addFastBrain(userId, usd);
        } catch (RuntimeException e) {
            log.warn("Cerveau rapide : coût non inscrit ({})", e.getClass().getSimpleName());
        }
    }

    /** Le plafond du mois en euros et ce qui en est dépensé (lecture admin, mesure). */
    public BigDecimal monthSpentEur(UUID userId) {
        return usage.monthFastBrainCostUsd(userId).multiply(usdToEur).setScale(2, RoundingMode.HALF_UP);
    }

    private static String text(JsonNode input, String field) {
        if (input == null) {
            return "";
        }
        JsonNode node = input.get(field);
        return node == null || node.isNull() ? "" : node.asText("").strip();
    }
}
