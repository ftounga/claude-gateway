package fr.claudegateway.jarvis.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.jarvis.JarvisCostService;
import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushNotificationService;

/**
 * <b>Le plafond de Jarvis</b> (F-244 / SF-244-03) : un montant mensuel global en euros, par compte, qui prévient, demande
 * avant de déléguer et ne s'emballe jamais (D6).
 *
 * <ul>
 * <li>sous 80 % : rien ;</li>
 * <li>à 80 % : une notification (F-185), <b>une seule</b> par mois ;</li>
 * <li>à 100 % (souple) : le cerveau rapide continue ; une délégation à Opus ne part que sur un « oui » ;</li>
 * <li>à 2 × (dur) : aucune délégation, aucun appel au cerveau rapide — une phrase fixe.</li>
 * </ul>
 *
 * <p><b>Justesse avant coût</b> : le budget décide seulement si une délégation <b>démarre</b>. Il ne coupe jamais un tour
 * en cours, ne change jamais de modèle, et ne déclenche jamais une délégation (règle F-239 : pas d'escalade de coût).</p>
 *
 * <p>BYOK (D7) : le coût reste compté (c'est une dépense réelle), mais le plafond ne bloque qu'en Hosted, sauf si le compte
 * a coché « appliquer aussi en BYOK ». Isolation : toutes les lectures et écritures sont sous le {@code user_id} du
 * principal.</p>
 */
@Service
public class JarvisBudgetService {

    private static final Logger log = LoggerFactory.getLogger(JarvisBudgetService.class);

    /** L'état du budget du mois. */
    public enum State { NONE, ALERT, SOFT, HARD }

    /** Plafond maximal réglable (garde-fou de saisie). */
    static final BigDecimal MAX_CAP_EUR = new BigDecimal("10000");
    /** Un « oui » vaut pour la délégation qui suit, pas au-delà. */
    static final Duration CONFIRMATION_TTL = Duration.ofMinutes(2);
    static final BigDecimal ALERT_RATIO = new BigDecimal("0.80");
    static final BigDecimal HARD_RATIO = new BigDecimal("2");

    /** La phrase dite au plafond dur (aucun appel modèle). */
    public static final String HARD_REPLY = "Monsieur, le budget de Jarvis de ce mois est épuisé.";

    /**
     * L'état du budget pour l'écran : plafond, dépense du mois (en euros), part consommée, état, et s'il s'applique.
     *
     * @param applies le plafond bloque-t-il pour ce compte (Hosted, ou BYOK avec l'option) ?
     */
    public record Status(BigDecimal monthlyCapEur, boolean applyOnByok, BigDecimal monthSpentEur, int percent,
            State state, boolean applies) {
    }

    private final JarvisBudgetRepository repository;
    private final JarvisCostService costs;
    private final Clock clock;
    private final BigDecimal defaultCapEur;
    private ByokKeyService byok;
    private PushNotificationService push;
    /** Les « oui » donnés à une délégation au-delà du plafond, par compte (usage unique, {@link #CONFIRMATION_TTL}). */
    private final Map<UUID, Instant> confirmations = new ConcurrentHashMap<>();

    public JarvisBudgetService(JarvisBudgetRepository repository, JarvisCostService costs, Clock clock,
            @Value("${app.jarvis.budget.monthly-cap-eur:150}") BigDecimal defaultCapEur) {
        this.repository = repository;
        this.costs = costs;
        this.clock = clock;
        this.defaultCapEur = defaultCapEur == null || defaultCapEur.signum() < 0 ? new BigDecimal("150")
                : defaultCapEur.setScale(2, RoundingMode.HALF_UP);
    }

    @Autowired(required = false)
    public void setByok(ByokKeyService byok) {
        this.byok = byok;
    }

    @Autowired(required = false)
    public void setPush(PushNotificationService push) {
        this.push = push;
    }

    /** L'état du budget du compte ; envoie l'alerte 80 % si elle est due (une fois par mois). */
    public Status status(UUID userId) {
        Optional<JarvisBudget> row = repository.findById(userId);
        BigDecimal cap = row.map(JarvisBudget::getMonthlyCapEur).orElse(defaultCapEur);
        boolean onByok = row.map(JarvisBudget::isApplyOnByok).orElse(false);
        BigDecimal spent = costs.costs(userId).month().total();
        State state = stateOf(spent, cap);
        if (state != State.NONE) {
            alertOnce(userId, row.orElse(null), cap);
        }
        return new Status(cap, onByok, spent, percentOf(spent, cap), state, onByok || hosted(userId));
    }

    /** L'état d'une dépense face à un plafond : 80 %, 100 %, 2 ×. Un plafond nul est dur d'emblée. */
    static State stateOf(BigDecimal spentEur, BigDecimal capEur) {
        if (capEur == null || capEur.signum() <= 0) {
            return State.HARD;
        }
        BigDecimal spent = spentEur == null ? BigDecimal.ZERO : spentEur;
        if (spent.compareTo(capEur.multiply(HARD_RATIO)) >= 0) {
            return State.HARD;
        }
        if (spent.compareTo(capEur) >= 0) {
            return State.SOFT;
        }
        return spent.compareTo(capEur.multiply(ALERT_RATIO)) >= 0 ? State.ALERT : State.NONE;
    }

    static int percentOf(BigDecimal spentEur, BigDecimal capEur) {
        if (capEur == null || capEur.signum() <= 0) {
            return 100;
        }
        return (spentEur == null ? BigDecimal.ZERO : spentEur).multiply(BigDecimal.valueOf(100))
                .divide(capEur, 0, RoundingMode.DOWN).intValue();
    }

    /**
     * Règle le plafond et l'option BYOK ; l'état change immédiatement. {@code null} garde la valeur actuelle.
     *
     * @throws IllegalArgumentException montant négatif ou au-delà de {@link #MAX_CAP_EUR}
     */
    public Status update(UUID userId, BigDecimal monthlyCapEur, Boolean applyOnByok) {
        if (monthlyCapEur != null && (monthlyCapEur.signum() < 0 || monthlyCapEur.compareTo(MAX_CAP_EUR) > 0)) {
            throw new IllegalArgumentException("Le plafond doit être compris entre 0 et 10 000 €.");
        }
        JarvisBudget row = repository.findById(userId)
                .orElseGet(() -> JarvisBudget.builder().userId(userId).monthlyCapEur(defaultCapEur).build());
        if (monthlyCapEur != null) {
            row.setMonthlyCapEur(monthlyCapEur.setScale(2, RoundingMode.HALF_UP));
        }
        if (applyOnByok != null) {
            row.setApplyOnByok(applyOnByok);
        }
        row.setUpdatedAt(OffsetDateTime.now(clock));
        repository.save(row);
        return status(userId);
    }

    /** Le « oui » du PO à une délégation au-delà du plafond : vaut pour la prochaine délégation, deux minutes. */
    public void confirm(UUID userId) {
        confirmations.put(userId, clock.instant());
    }

    /**
     * <b>La porte d'une délégation</b>, appelée AVANT tout appel au modèle d'un tour né de Jarvis. Plafond dur ⇒
     * refus ; plafond atteint ⇒ refus sauf « oui » récent (consommé) ; sinon rien. Ne s'applique pas en BYOK sans
     * l'option (D7).
     *
     * @param hosted le tour tourne sur la clé de la plateforme
     * @throws JarvisBudgetException {@code jarvis_budget_hard} ou {@code jarvis_budget_confirm}, avec la phrase à dire
     */
    public void gateDelegation(UUID userId, boolean hosted) {
        Status status;
        try {
            status = status(userId);
        } catch (RuntimeException e) {
            // Illisible : on ne bloque pas un travail sur une lecture en panne (le plafond voix et le plafond rapide
            // restent des sous-bornes).
            log.warn("Budget Jarvis illisible ({}) : délégation laissée partir", e.getClass().getSimpleName());
            return;
        }
        if (!(hosted || status.applyOnByok())) {
            return;
        }
        if (status.state() == State.HARD) {
            throw new JarvisBudgetException(JarvisBudgetException.HARD, HARD_REPLY);
        }
        if (status.state() == State.SOFT && !consumeConfirmation(userId)) {
            throw new JarvisBudgetException(JarvisBudgetException.CONFIRM, confirmPhrase(estimateEur(userId)));
        }
    }

    /** Vrai si le cerveau rapide doit se taire (plafond dur qui s'applique au compte). */
    public boolean hardStop(UUID userId) {
        try {
            Status status = status(userId);
            return status.state() == State.HARD && status.applies();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** La phrase de confirmation, au montant estimé d'une recherche. */
    static String confirmPhrase(int estimateEur) {
        return "Budget Jarvis du mois atteint ; cette recherche coûte environ " + estimateEur + " €. Je la lance ?";
    }

    /** Le coût moyen d'une recherche Opus de Jarvis ce mois-ci, arrondi à l'euro, au moins 1 €. */
    int estimateEur(UUID userId) {
        try {
            JarvisCostService.Costs month = costs.costs(userId).month();
            if (month.opusTurns() <= 0) {
                return 1;
            }
            int avg = month.opus().divide(BigDecimal.valueOf(month.opusTurns()), 0, RoundingMode.HALF_UP).intValue();
            return Math.max(1, avg);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private boolean consumeConfirmation(UUID userId) {
        Instant at = confirmations.remove(userId);
        return at != null && !at.plus(CONFIRMATION_TTL).isBefore(clock.instant());
    }

    private boolean hosted(UUID userId) {
        try {
            return byok == null || byok.resolveActiveApiKey(userId).isEmpty();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** L'alerte 80 % (F-185), une seule fois dans le mois ; best-effort. */
    private void alertOnce(UUID userId, JarvisBudget row, BigDecimal cap) {
        String month = YearMonth.now(clock.withZone(JarvisCostServiceZone.ZONE)).toString();
        if (row != null && month.equals(row.getAlert80SentMonth())) {
            return;
        }
        try {
            JarvisBudget marked = row != null ? row
                    : JarvisBudget.builder().userId(userId).monthlyCapEur(cap).build();
            marked.setAlert80SentMonth(month);
            marked.setUpdatedAt(OffsetDateTime.now(clock));
            repository.save(marked);
            if (push != null) {
                push.notify(userId, null, PushEvent.JARVIS_BUDGET_ALERT, "/jarvis");
            }
        } catch (RuntimeException e) {
            log.warn("Alerte du budget Jarvis non envoyée ({})", e.getClass().getSimpleName());
        }
    }

    /** Le fuseau du mois de Jarvis (le même que son compteur). */
    static final class JarvisCostServiceZone {
        static final java.time.ZoneId ZONE = java.time.ZoneId.of("Europe/Paris");

        private JarvisCostServiceZone() {
        }
    }
}
