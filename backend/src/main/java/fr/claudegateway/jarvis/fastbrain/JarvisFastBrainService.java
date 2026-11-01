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
            long firstTokenMs, BigDecimal costUsd) {
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
        Sink out = sink == null ? delta -> { } : sink;
        UUID userId = request.userId();
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
        AgentTurnRequest call = new AgentTurnRequest(properties.model(), JarvisFastBrainPrompt.system(),
                messages(request), List.of(JarvisFastBrainPrompt.delegateTool()), apiKey,
                AgentReasoning.off(properties.effort()), AgentContextPolicy.none(), AgentTurnMode.ACT,
                AgentCallShape.compact(properties.maxTokens()));

        long started = System.nanoTime();
        AtomicLong firstToken = new AtomicLong(-1L);
        CountDownLatch first = new CountDownLatch(1);
        StringBuilder streamed = new StringBuilder();
        Object gate = new Object();
        boolean[] abandoned = { false };
        CompletableFuture<AgentTurn> pending = CompletableFuture.supplyAsync(() -> provider.nextTurn(call, delta -> {
            if (firstToken.compareAndSet(-1L, (System.nanoTime() - started) / 1_000_000L)) {
                first.countDown();
            }
            synchronized (gate) {
                if (!abandoned[0]) {
                    streamed.append(delta);
                    out.text(delta);
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
                synchronized (gate) {
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
            return auto(request, REASON_REFUSAL, firstMs);
        }
        Optional<AgentToolCall> delegate = turn.toolCalls().stream()
                .filter(tc -> JarvisFastBrainPrompt.TOOL_NAME.equals(tc.name())).findFirst();
        if (delegate.isPresent()) {
            return new Outcome(Kind.DELEGATE, streamed.toString().strip(),
                    delegation(request, delegate.get().input()), null, properties.model(), firstMs, null);
        }
        String text = turn.text() == null ? "" : turn.text().strip();
        if (text.isEmpty() || turn.truncated()) {
            return auto(request, REASON_EMPTY, firstMs);
        }
        return new Outcome(Kind.ANSWER, text, null, null, properties.model(), firstMs, null);
    }

    /** Les messages de l'appel : la parole et son contexte, dans le message (jamais dans la consigne système). */
    List<AgentMessage> messages(Request request) {
        List<AgentMessage> messages = new ArrayList<>(1);
        messages.add(AgentMessage.userText(message(context(request), request.text())));
        return messages;
    }

    /** Le message d'un échange : le contexte, puis la parole. */
    static String message(String context, String text) {
        return "[Contexte]\n" + (context == null ? "" : context.strip()) + "\n\n[Parole de l'utilisateur]\n"
                + (text == null ? "" : text.strip());
    }

    /** Le contexte minimal : l'heure, le sujet et le chantier courants (enrichi par SF-235-02). */
    String context(Request request) {
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
        UUID subject = request.workspace() == null ? null : request.workspace().getId();
        String said = request.text() == null ? "" : request.text().strip();
        return new Outcome(Kind.DELEGATE, "", new Delegation(UUID.randomUUID(), said, "", AUTO_ANNOUNCE, subject,
                request.chantier(), true, reason), reason, properties.model(), firstMs, null);
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
