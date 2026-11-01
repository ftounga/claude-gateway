package fr.claudegateway.atelier.veille;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ChatMessage;
import fr.claudegateway.ai.ChatRole;
import fr.claudegateway.atelier.AtelierProperties;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushNotificationService;
import fr.claudegateway.quota.ProviderCostCalculator;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.quota.TurnCost;
import fr.claudegateway.quota.TurnExtras;
import fr.claudegateway.quota.TurnTokens;
import fr.claudegateway.quota.UsageLedgerService;

/**
 * <b>La veille t'interpelle</b> (F-230 / SF-230-03, D4) : sur changement <b>seulement</b>, un appel léger au
 * fournisseur — par {@link AIProvider}, jamais Anthropic en direct — qui ne reçoit <b>que le diff</b> et le
 * périmètre, rédige une phrase ; l'alerte est rangée, la notification sonne, la ligne est posée dans le fil.
 *
 * <p><b>Jamais muette</b> : budget de la veille épuisé, quota du compte atteint ou fournisseur en échec ⇒ phrase
 * déterministe ({@link JarvisWatchDiff#plainSummary}), notification quand même.</p>
 *
 * <p><b>Préfixe stable</b> : la consigne système est un littéral ; tout ce qui change (diff, périmètre) va dans le
 * message. <b>Aucun contenu journalisé</b> : seule la classe d'une erreur l'est.</p>
 */
@Component
public class JarvisWatchAlerter implements JarvisWatchChangeListener {

    /** Diff transmis au plus (caractères) : au-delà, la fin est coupée et signalée. */
    static final int MAX_DIFF_CHARS = 12_000;
    /** Plafond de sortie : une ou deux phrases. */
    static final int MAX_TOKENS = 300;
    static final int MAX_SENTENCE = 500;

    static final String CONSIGNE = """
            Tu es Jarvis, l'assistant d'un chef de projet. Une veille automatique compare, du lundi au vendredi, \
            la liste des tickets d'une plateforme avec le passage précédent. On te donne la plateforme, le \
            périmètre suivi et UNIQUEMENT les changements détectés (NEW = entré dans le périmètre, GONE = sorti, \
            STATUS = statut changé avant → après, ASSIGNEE = assigné changé, COMMENT = nouveau commentaire, \
            UPDATED = autre mise à jour). Écris UNE ou DEUX phrases en français, au tutoiement, qui disent ce qui \
            a bougé et ce qui mérite son attention d'abord (un blocage, un nouveau ticket qui lui est assigné). \
            Cite les identifiants utiles. N'invente rien au-delà des changements donnés ; pas de liste, pas de \
            titre, pas de formule de politesse. Commence par le nom de la veille suivi de deux-points.""";

    private static final Logger log = LoggerFactory.getLogger(JarvisWatchAlerter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AIProvider aiProvider;
    private final JarvisWatchAlertRepository alerts;
    private final JarvisAlertLinks links;
    private final JarvisWatchThreadNote threadNote;
    private final Clock clock;
    private final String model;
    private ByokKeyService byok;
    private UsageLedgerService ledger;
    private ProviderCostCalculator costs;
    private QuotaService quota;
    private PushNotificationService push;

    public JarvisWatchAlerter(AIProvider aiProvider, JarvisWatchAlertRepository alerts, JarvisAlertLinks links,
            JarvisWatchThreadNote threadNote, Clock clock, AtelierProperties atelier,
            @Value("${app.jarvis.watches.model:}") String model) {
        this.aiProvider = aiProvider;
        this.alerts = alerts;
        this.links = links;
        this.threadNote = threadNote;
        this.clock = clock;
        this.model = model != null && !model.isBlank() ? model.strip() : atelier.model();
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
    public void setPush(PushNotificationService push) {
        this.push = push;
    }

    /** F-232 / SF-232-03 : le compagnon Android dit l'alerte à voix haute. {@code null} ⇒ rien, comme avant. */
    private fr.claudegateway.jarvis.companion.JarvisCompanionAnnouncements companion;

    @Autowired(required = false)
    public void setCompanion(fr.claudegateway.jarvis.companion.JarvisCompanionAnnouncements companion) {
        this.companion = companion;
    }

    @Override
    public void onChange(JarvisWatch watch, Workspace workspace, JarvisWatchRun run,
            List<JarvisWatchDiff.Change> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        Sentence sentence = write(watch, workspace, changes);
        JarvisWatchAlert alert = alerts.save(JarvisWatchAlert.builder().userId(watch.getUserId())
                .workspaceId(watch.getWorkspaceId()).watchId(watch.getId()).runId(run == null ? null : run.getId())
                .chantierNumber(watch.getChantierNumber()).sentence(sentence.text()).changesJson(json(changes))
                .changeCount(changes.size()).aiWritten(sentence.ai()).costUsd(sentence.costUsd())
                .createdAt(OffsetDateTime.now(clock)).build());
        threadNote.post(watch, sentence.text());
        if (push != null) {
            try {
                push.notify(watch.getUserId(), watch.getWorkspaceId(), PushEvent.WATCH_CHANGED,
                        links.linkFor(alert.getId(), watch.getWorkspaceId()));
            } catch (RuntimeException e) {
                // Best-effort : la notification ne fait jamais échouer une alerte.
            }
        }
        if (companion != null) {
            // Best-effort elle aussi (le service n'échoue jamais vers l'appelant).
            companion.watchAlert(watch.getUserId(), sentence.text(),
                    links.linkFor(alert.getId(), watch.getWorkspaceId()));
        }
    }

    /** La phrase et son coût. */
    record Sentence(String text, boolean ai, BigDecimal costUsd) {
    }

    /** La phrase de l'IA si le budget, le quota et le fournisseur le permettent ; sinon la phrase déterministe. */
    Sentence write(JarvisWatch watch, Workspace workspace, List<JarvisWatchDiff.Change> changes) {
        Sentence plain = new Sentence(JarvisWatchDiff.plainSummary(watch.getName(), changes), false, BigDecimal.ZERO);
        if (!withinBudget(watch)) {
            return plain;
        }
        try {
            String apiKey = byok == null ? null : byok.resolveActiveApiKey(watch.getUserId()).orElse(null);
            ChatCompletionResult result = aiProvider.complete(new ChatCompletionRequest(model,
                    List.of(new ChatMessage(ChatRole.USER, material(watch, changes))), List.of(), apiKey, CONSIGNE,
                    MAX_TOKENS, true));
            BigDecimal cost = record(watch, workspace, result);
            String text = result == null || result.content() == null ? "" : result.content().strip();
            if (text.isEmpty()) {
                return new Sentence(plain.text(), false, cost);
            }
            return new Sentence(text.length() <= MAX_SENTENCE ? text : text.substring(0, MAX_SENTENCE - 1) + "…", true,
                    cost);
        } catch (RuntimeException e) {
            log.warn("Veille : phrase non rédigée par le fournisseur ({})", e.getClass().getSimpleName());
            return plain;
        }
    }

    /** Budget du mois de la veille, puis quota du compte (garde du tour). */
    boolean withinBudget(JarvisWatch watch) {
        BigDecimal monthly = watch.getMonthlyBudgetUsd() == null ? JarvisWatchService.DEFAULT_MONTHLY_BUDGET_USD
                : watch.getMonthlyBudgetUsd();
        BigDecimal spent = alerts.spentSince(watch.getId(), watch.getUserId(),
                JarvisWatchService.monthStart(OffsetDateTime.now(clock)));
        if (spent != null && spent.compareTo(monthly) >= 0) {
            return false;
        }
        if (quota != null) {
            try {
                quota.assertWithinQuota(watch.getUserId());
            } catch (RuntimeException e) {
                return false; // quota atteint (ou illisible) : la phrase déterministe suffit
            }
        }
        return true;
    }

    /** Ce que le fournisseur lit : plateforme, périmètre, diff — rien d'autre. */
    static String material(JarvisWatch watch, List<JarvisWatchDiff.Change> changes) {
        String diff = json(changes);
        boolean cut = diff.length() > MAX_DIFF_CHARS;
        if (cut) {
            diff = diff.substring(0, MAX_DIFF_CHARS);
        }
        return "Veille : " + watch.getName() + "\nPlateforme : " + watch.getPlatform() + "\nPérimètre : "
                + watch.getScope() + "\nChangements (" + changes.size() + ")" + (cut ? ", liste coupée" : "") + " :\n"
                + diff;
    }

    /** Coût de l'appel au journal d'usage ; rend le montant en dollars (0 s'il n'a pu être établi). */
    private BigDecimal record(JarvisWatch watch, Workspace workspace, ChatCompletionResult result) {
        if (result == null || costs == null) {
            return BigDecimal.ZERO;
        }
        try {
            TurnTokens tokens = result.turnTokens();
            TurnCost cost = costs.calculate(tokens, result.model());
            if (ledger != null) {
                ledger.recordTurn(watch.getUserId(), watch.getWorkspaceId(),
                        workspace == null ? null : workspace.getHostId(), tokens, TurnExtras.NONE, cost);
            }
            return cost == null ? BigDecimal.ZERO : cost.amountUsd();
        } catch (RuntimeException e) {
            log.warn("Veille : consommation non inscrite au journal ({})", e.getClass().getSimpleName());
            return BigDecimal.ZERO;
        }
    }

    static String json(List<JarvisWatchDiff.Change> changes) {
        try {
            return MAPPER.writeValueAsString(changes);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "[]";
        }
    }
}
