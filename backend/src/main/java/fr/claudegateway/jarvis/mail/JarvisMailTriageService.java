package fr.claudegateway.jarvis.mail;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.agent.AgentCallShape;
import fr.claudegateway.agent.AgentContextPolicy;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentReasoning;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnMode;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.jarvis.budget.JarvisBudgetService;
import fr.claudegateway.jarvis.fastbrain.JarvisFastBrainService;
import fr.claudegateway.quota.ProviderCostCalculator;
import fr.claudegateway.quota.TurnCost;
import fr.claudegateway.quota.TurnTokens;
import fr.claudegateway.quota.UsageLedgerService;
import fr.claudegateway.quota.UsageTag;
import fr.claudegateway.quota.UsageTurn;

/**
 * <b>Le tri du courrier</b> (F-242 / SF-242-03, D6, D7, D9 a-b) — d'abord des règles sans IA, puis <b>un</b> appel du
 * cerveau rapide par lot de 20, consigne figée ({@link JarvisMailTriagePrompt}), <b>aucun outil</b>, sortie validée
 * strictement ({@link JarvisMailTriageParser}). En cas de doute, jamais « à ignorer ».
 *
 * <p>Provider Independence : l'appel passe par {@link AiAgentProvider}. Le coût est inscrit au journal en annexe
 * étiquetée {@code JARVIS / MAIL} (hors quota Gateway, F-244) et à la ligne « Courrier » de la journée Jarvis.</p>
 *
 * <p><b>Isolation</b> : un lot ne contient que les courriers d'<b>un</b> compte, lus par {@code user_id}.</p>
 */
@Service
public class JarvisMailTriageService implements JarvisMailTriageTrigger {

    private static final Logger log = LoggerFactory.getLogger(JarvisMailTriageService.class);

    public static final int BATCH = 20;
    static final int MAX_BATCHES = 5;
    static final String RULE_BULK = "Envoi de masse";
    static final String BUDGET_STOP = "Budget Jarvis atteint";
    static final String CORRECTED = "Corrigé par vous";
    static final UsageTag MAIL_TAG = UsageTag.jarvis(UsageTurn.JarvisPart.MAIL);

    private final JarvisMailItemRepository items;
    private final JarvisMailItemService itemService;
    private final AiAgentProvider provider;
    private final JarvisFastBrainService fastBrain;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    private UsageLedgerService ledger;
    private ProviderCostCalculator costs;
    private JarvisUsageService usage;
    private JarvisBudgetService budget;

    public JarvisMailTriageService(JarvisMailItemRepository items, JarvisMailItemService itemService,
            AiAgentProvider provider, JarvisFastBrainService fastBrain, Clock clock) {
        this.items = items;
        this.itemService = itemService;
        this.provider = provider;
        this.fastBrain = fastBrain;
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setAccounting(UsageLedgerService ledger, ProviderCostCalculator costs, JarvisUsageService usage) {
        this.ledger = ledger;
        this.costs = costs;
        this.usage = usage;
    }

    @Autowired(required = false)
    public void setBudget(JarvisBudgetService budget) {
        this.budget = budget;
    }

    /** Asynchrone, un tri à la fois par compte ; jamais pendant un tour de voix. */
    @Override
    public void triageSoon(UUID userId) {
        if (userId == null || !running.add(userId)) {
            return;
        }
        executor.execute(() -> {
            try {
                triageUser(userId);
            } catch (RuntimeException e) {
                log.warn("Courrier : tri interrompu ({})", e.getClass().getSimpleName());
            } finally {
                running.remove(userId);
            }
        });
    }

    /** Rattrapage : les comptes qui ont encore des courriers en attente (après une panne, un redémarrage). */
    public void triagePending() {
        for (UUID userId : items.usersWithTriage(JarvisMailItem.Triage.EN_ATTENTE, PageRequest.of(0, 50))) {
            triageSoon(userId);
        }
    }

    /** Trie les courriers en attente du compte (5 lots de 20 au plus par passage). Rend le nombre de courriers triés. */
    public int triageUser(UUID userId) {
        int done = 0;
        for (int b = 0; b < MAX_BATCHES; b++) {
            List<JarvisMailItem> pending = items.findByUserIdAndTriageOrderByReceivedAtAsc(userId,
                    JarvisMailItem.Triage.EN_ATTENTE, PageRequest.of(0, BATCH));
            if (pending.isEmpty()) {
                break;
            }
            done += triageBatch(userId, pending);
            if (pending.size() < BATCH) {
                break;
            }
        }
        return done;
    }

    int triageBatch(UUID userId, List<JarvisMailItem> pending) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Map<String, JarvisMailItem> byToken = new LinkedHashMap<>();
        List<JarvisMailTriagePrompt.Entry> entries = new ArrayList<>();
        List<JarvisMailItem> decided = new ArrayList<>();
        for (JarvisMailItem item : pending) {
            if (!userId.equals(item.getUserId())) {
                continue; // jamais : défense en profondeur de l'isolation
            }
            MailHeaders h = itemService.headers(item);
            if (ruleIgnores(item, h)) {
                set(item, JarvisMailItem.Triage.A_IGNORER, RULE_BULK, now);
                decided.add(item);
                continue;
            }
            String token = "m" + (entries.size() + 1);
            byToken.put(token, item);
            entries.add(new JarvisMailTriagePrompt.Entry(token, h));
        }
        if (!entries.isEmpty()) {
            Map<String, JarvisMailTriageParser.Verdict> verdicts = budget != null && budget.hardStop(userId)
                    ? Map.of() : ask(userId, entries, byToken.keySet());
            String fallback = budget != null && budget.hardStop(userId) ? BUDGET_STOP : JarvisMailTriageParser.UNSURE;
            for (Map.Entry<String, JarvisMailItem> e : byToken.entrySet()) {
                JarvisMailTriageParser.Verdict v = verdicts.get(e.getKey());
                if (v == null) {
                    set(e.getValue(), JarvisMailItem.Triage.A_LIRE, fallback, now);
                } else {
                    set(e.getValue(), v.triage(), v.reason(), now);
                }
                decided.add(e.getValue());
            }
        }
        items.saveAll(decided);
        return decided.size();
    }

    /** D6 — « à ignorer » sans IA : envoi de masse, sauf s'il répond à un fil (le modèle tranche alors). */
    static boolean ruleIgnores(JarvisMailItem item, MailHeaders h) {
        if (!item.isBulk()) {
            return false;
        }
        boolean reply = item.getThreadRef() != null
                && h.subject().strip().toLowerCase(Locale.ROOT).matches("^(re|tr|fwd?)\\s*:.*");
        return !reply;
    }

    private Map<String, JarvisMailTriageParser.Verdict> ask(UUID userId, List<JarvisMailTriagePrompt.Entry> entries,
            Set<String> tokens) {
        try {
            AgentTurn turn = provider.nextTurn(new AgentTurnRequest(fastBrain.model(), JarvisMailTriagePrompt.SYSTEM,
                    List.of(AgentMessage.userText(JarvisMailTriagePrompt.message(entries))), List.of(),
                    fastBrain.apiKeyFor(userId), AgentReasoning.none(), AgentContextPolicy.none(), AgentTurnMode.ACT,
                    new AgentCallShape(1_500, false, false, false)));
            account(userId, turn);
            return turn == null ? Map.of() : JarvisMailTriageParser.parse(turn.text(), tokens);
        } catch (RuntimeException e) {
            log.warn("Courrier : tri indisponible ({})", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    /** Le coût de l'appel : journal (annexe JARVIS / MAIL, hors quota Gateway) et ligne « Courrier ». Best-effort. */
    private void account(UUID userId, AgentTurn turn) {
        if (turn == null) {
            return;
        }
        try {
            String model = turn.servedModel() != null ? turn.servedModel() : fastBrain.model();
            if (ledger != null) {
                ledger.recordAnnex(userId, null, model, turn.inputTokens(), turn.outputTokens(),
                        turn.cacheReadTokens(), turn.cacheWriteTokens(), MAIL_TAG);
            }
            BigDecimal usd = BigDecimal.ZERO;
            if (costs != null) {
                TurnCost cost = costs.calculate(new TurnTokens(turn.fullPriceInputTokens(), turn.outputTokens(),
                        turn.cacheReadTokens(), turn.cacheWriteTokens()), model);
                usd = cost == null || cost.amountUsd() == null ? BigDecimal.ZERO : cost.amountUsd();
            }
            if (usage != null) {
                usage.addMailCost(userId, usd);
            }
        } catch (RuntimeException e) {
            log.warn("Courrier : coût du tri non inscrit ({})", e.getClass().getSimpleName());
        }
    }

    /** SF-242-03 — corriger le tri au toucher (compté). */
    @Transactional
    public JarvisMailItemService.ItemView correct(UUID userId, UUID itemId, String rawTriage) {
        JarvisMailItem.Triage triage = rawTriage == null ? null : JarvisMailTriageParser.triage(rawTriage);
        if (triage == null) {
            throw new JarvisMailExceptions.InvalidMailAccountException("Tri inconnu (A_TRAITER, A_LIRE ou A_IGNORER).");
        }
        JarvisMailItem item = itemService.require(userId, itemId);
        boolean changed = item.getTriage() != triage;
        set(item, triage, CORRECTED, OffsetDateTime.now(clock));
        items.save(item);
        if (changed && usage != null) {
            usage.addMailTriageCorrected(userId);
        }
        return itemService.views(userId, List.of(item)).get(0);
    }

    private static void set(JarvisMailItem item, JarvisMailItem.Triage triage, String reason, OffsetDateTime now) {
        item.setTriage(triage);
        item.setTriageReason(reason);
        item.setTriagedAt(now);
    }
}
