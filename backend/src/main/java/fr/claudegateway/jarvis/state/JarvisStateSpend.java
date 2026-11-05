package fr.claudegateway.jarvis.state;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.jarvis.JarvisCostService;
import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.jarvis.budget.JarvisBudgetService;
import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushNotificationService;
import fr.claudegateway.quota.ProviderCostCalculator;
import fr.claudegateway.quota.TurnCost;
import fr.claudegateway.quota.TurnTokens;
import fr.claudegateway.quota.UsageLedgerService;
import fr.claudegateway.quota.UsageTag;
import fr.claudegateway.quota.UsageTurn;

/**
 * <b>La dépense du registre d'état, bornée et mesurée en euros</b> (F-247, D16) : les deux seuls appels modèle du
 * registre — la lecture des captures (part {@code CAPTURE}, 10 €/mois) et les constats rédigés (part {@code ETAT},
 * 15 €/mois) — ont chacun leur plafond propre, réglable, <b>dans</b> le budget Jarvis F-244 (origine {@code JARVIS},
 * hors quota Gateway). Au plafond, la source s'arrête, le PO est prévenu <b>une fois</b> par mois, sans escalade.
 */
@Service
public class JarvisStateSpend {

    private static final Logger log = LoggerFactory.getLogger(JarvisStateSpend.class);
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    /** Une des deux sources payantes. */
    public enum Part {
        CAPTURE(UsageTurn.JarvisPart.CAPTURE, "CAPTURE_READ"),
        NOTES(UsageTurn.JarvisPart.ETAT, "STATE_NOTES");

        final UsageTurn.JarvisPart ledgerPart;
        final String jobKind;

        Part(UsageTurn.JarvisPart ledgerPart, String jobKind) {
            this.ledgerPart = ledgerPart;
            this.jobKind = jobKind;
        }
    }

    private final JarvisUsageService usage;
    private final JarvisCostService costs;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final BigDecimal captureCapEur;
    private final BigDecimal notesCapEur;

    private UsageLedgerService ledger;
    private ProviderCostCalculator calculator;
    private JarvisBudgetService budget;
    private PushNotificationService push;

    public JarvisStateSpend(JarvisUsageService usage, JarvisCostService costs, JdbcTemplate jdbc, Clock clock,
            @Value("${app.jarvis.state.capture-cap-eur:10}") BigDecimal captureCapEur,
            @Value("${app.jarvis.state.notes-cap-eur:15}") BigDecimal notesCapEur) {
        this.usage = usage;
        this.costs = costs;
        this.jdbc = jdbc;
        this.clock = clock;
        this.captureCapEur = captureCapEur;
        this.notesCapEur = notesCapEur;
    }

    @Autowired(required = false)
    public void setAccounting(UsageLedgerService ledger, ProviderCostCalculator calculator) {
        this.ledger = ledger;
        this.calculator = calculator;
    }

    @Autowired(required = false)
    public void setBudget(JarvisBudgetService budget) {
        this.budget = budget;
    }

    @Autowired(required = false)
    public void setPush(PushNotificationService push) {
        this.push = push;
    }

    /** La dépense du mois d'une part, en euros. */
    public BigDecimal monthEur(UUID userId, Part part) {
        BigDecimal usd = part == Part.CAPTURE ? usage.monthCaptureCostUsd(userId) : usage.monthNotesCostUsd(userId);
        return costs.toEur(usd);
    }

    /** Le plafond propre d'une part, en euros. */
    public BigDecimal capEur(Part part) {
        return part == Part.CAPTURE ? captureCapEur : notesCapEur;
    }

    /**
     * Peut-on encore dépenser pour cette part ? Non si son plafond propre est atteint (le PO est alors prévenu une fois
     * par mois) ou si le budget Jarvis est à l'arrêt dur. Jamais d'exception : en cas de doute, on s'arrête.
     */
    public boolean maySpend(UUID userId, Part part) {
        try {
            if (budget != null && budget.hardStop(userId)) {
                return false;
            }
            if (monthEur(userId, part).compareTo(capEur(part)) >= 0) {
                notifyOnce(userId, part);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("Registre d'état : dépense non vérifiable ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    /** Inscrit le coût d'un appel (journal JARVIS hors quota, et la ligne du jour) ; rend le coût en dollars. */
    public BigDecimal account(UUID userId, UUID workspaceId, Part part, AgentTurn turn, String fallbackModel) {
        if (turn == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal usd = BigDecimal.ZERO;
        try {
            String model = turn.servedModel() != null ? turn.servedModel() : fallbackModel;
            if (ledger != null) {
                ledger.recordAnnex(userId, workspaceId, model, turn.inputTokens(), turn.outputTokens(),
                        turn.cacheReadTokens(), turn.cacheWriteTokens(), UsageTag.jarvis(part.ledgerPart));
            }
            if (calculator != null) {
                TurnCost cost = calculator.calculate(new TurnTokens(turn.fullPriceInputTokens(), turn.outputTokens(),
                        turn.cacheReadTokens(), turn.cacheWriteTokens()), model);
                usd = cost == null || cost.amountUsd() == null ? BigDecimal.ZERO : cost.amountUsd();
            }
            if (part == Part.CAPTURE) {
                usage.addCaptureCost(userId, usd);
            } else {
                usage.addNotesCost(userId, usd);
            }
        } catch (RuntimeException e) {
            log.warn("Registre d'état : coût non inscrit ({})", e.getClass().getSimpleName());
        }
        return usd;
    }

    /** Prévient une seule fois par mois : la trace durable est le premier travail abandonné pour plafond. */
    void notifyOnce(UUID userId, Part part) {
        LocalDate first = LocalDate.now(clock.withZone(ZONE)).withDayOfMonth(1);
        Integer already = jdbc.queryForObject("SELECT count(*) FROM async_jobs WHERE user_id = ? AND kind = ? "
                + "AND status = 'DROPPED' AND error_code = 'cost_cap' AND created_at >= ?", Integer.class, userId,
                part.jobKind, Timestamp.from(first.atStartOfDay(ZONE).toInstant()));
        if ((already == null || already == 0) && push != null) {
            push.notify(userId, null, part == Part.CAPTURE ? PushEvent.JARVIS_CAPTURES_CAP : PushEvent.JARVIS_NOTES_CAP,
                    "/jarvis");
        }
    }
}
