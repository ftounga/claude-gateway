package fr.claudegateway.quota;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.billing.BillingProperties;
import fr.claudegateway.billing.Subscription;

/**
 * Résout la <b>fenêtre de quota</b> d'un abonnement (F-66) : sur quelle durée le plafond s'oppose,
 * et ce qu'il faut reporter des mois déjà clos de cette fenêtre.
 *
 * <p>Deux régimes, et un seul mérite une explication :</p>
 * <ul>
 *   <li><b>Abonnement payant</b> (ou essai expiré, ou offre résiliée) → la fenêtre est le
 *       <b>mois calendaire courant</b>, sans report. C'est le comportement de F-10, inchangé :
 *       l'allocation d'un plan est mensuelle, et elle se renouvelle réellement le 1er.</li>
 *   <li><b>Essai en cours</b> → la fenêtre couvre <b>tout l'essai</b>. L'essai n'est pas un mois :
 *       c'est une fenêtre de quelques jours posée n'importe où dans le calendrier. Laisser le
 *       compteur repartir à zéro au 1er du mois donnait à un essai à cheval <b>deux fois</b> son
 *       plafond — 400 000 jetons au lieu de 200 000, soit ≈ 3,60 $ au lieu de ≈ 1,80 $ de coût
 *       fournisseur.</li>
 * </ul>
 *
 * <p><b>Où commence un essai</b>, et pourquoi la fenêtre est <b>bornée</b>. Le début est la
 * <b>création de l'abonnement</b> — date exacte de tout essai provisionné par la gateway
 * ({@code SubscriptionService.provisionTrial}) — ou, à défaut, {@code trial_ends_at} moins la durée
 * configurée. Quel que soit le résultat, la fenêtre ne remonte <b>jamais</b> avant le <b>mois
 * précédent</b> : un essai se compte en jours, il ne peut chevaucher qu'un seul 1er du mois. Cette
 * borne protège le cas limite d'un abonnement ancien qui se retrouverait {@code trialing} — sans
 * elle, la fenêtre remonterait à la naissance du compte. Elle est volontairement conservatrice :
 * dans ce cas limite, la consommation du mois précédent compte contre l'essai. Mieux vaut un essai
 * trop strict qu'un plafond qui fuit.</p>
 *
 * <p><b>Isolation</b> : la seule lecture faite ici filtre sur {@code user_id}, celui porté par
 * l'abonnement du contexte de sécurité — jamais un paramètre client.</p>
 */
@Service
public class QuotaWindowService {

    private final UsageCounterRepository usageCounterRepository;
    private final EntitlementService entitlementService;
    private final BillingProperties billingProperties;
    private final Clock clock;

    public QuotaWindowService(
            UsageCounterRepository usageCounterRepository,
            EntitlementService entitlementService,
            BillingProperties billingProperties,
            Clock clock) {
        this.usageCounterRepository = usageCounterRepository;
        this.entitlementService = entitlementService;
        this.billingProperties = billingProperties;
        this.clock = clock;
    }

    /**
     * Fenêtre de quota de cet abonnement, report compris.
     *
     * @param subscription abonnement de l'utilisateur ({@code null} toléré → mois courant)
     * @return la fenêtre ; jamais {@code null}
     */
    @Transactional(readOnly = true)
    public QuotaWindow resolve(Subscription subscription) {
        LocalDate currentPeriodStart = currentPeriodStart();
        if (!entitlementService.hasActiveTrial(subscription)) {
            return QuotaWindow.ofCurrentMonth(currentPeriodStart);
        }
        LocalDate trialStart = trialStart(subscription);
        if (trialStart == null || !trialStart.isBefore(currentPeriodStart.plusMonths(1))) {
            // Début d'essai introuvable, ou postérieur au mois courant (horloge ou date aberrante) :
            // on retombe sur le mois courant plutôt que de rendre une fenêtre vide — une fenêtre
            // vide ne compterait aucune consommation, ce qui est exactement le défaut à éviter.
            return QuotaWindow.ofCurrentMonth(currentPeriodStart);
        }
        // Bornage dur : la fenêtre ne remonte jamais plus loin que le mois précédent. Un essai dure
        // des jours, il ne peut chevaucher qu'un seul 1er du mois ; et si un abonnement ancien se
        // retrouvait en essai, cette borne empêche la fenêtre de remonter à la naissance du compte.
        LocalDate earliest = currentPeriodStart.minusMonths(1);
        LocalDate windowPeriodStart = trialStart.withDayOfMonth(1);
        if (windowPeriodStart.isBefore(earliest)) {
            windowPeriodStart = earliest;
        }
        QuotaCarryOver carryOver = carryOver(subscription, windowPeriodStart, currentPeriodStart);
        return new QuotaWindow(
                windowPeriodStart,
                trialStart.isBefore(windowPeriodStart) ? windowPeriodStart : trialStart,
                trialEndDate(subscription, currentPeriodStart),
                carryOver.billedTokens(),
                carryOver.processedTokens(),
                carryOver.bonusTokens());
    }

    /**
     * Somme des compteurs des mois de la fenêtre <b>déjà clos</b> (le mois courant est lu par
     * l'appelant, qui travaille sur sa ligne vivante). Aucune lecture si la fenêtre tient dans le
     * mois courant.
     */
    private QuotaCarryOver carryOver(Subscription subscription, LocalDate windowPeriodStart,
            LocalDate currentPeriodStart) {
        if (!windowPeriodStart.isBefore(currentPeriodStart)) {
            return QuotaCarryOver.NONE;
        }
        List<UsageCounter> counters = usageCounterRepository
                .findByUserIdAndPeriodStartGreaterThanEqual(subscription.getUserId(), windowPeriodStart);
        long billed = 0L;
        long processed = 0L;
        long bonus = 0L;
        for (UsageCounter counter : counters) {
            if (counter.getPeriodStart() != null && counter.getPeriodStart().isBefore(currentPeriodStart)) {
                billed += counter.getBilledTokens();
                processed += counter.totalTokens();
                bonus += counter.getBonusTokens();
            }
        }
        return new QuotaCarryOver(billed, processed, bonus);
    }

    /**
     * Jour de début de l'essai : la <b>création de l'abonnement</b>, date exacte de tout essai
     * provisionné par la gateway. À défaut (donnée ancienne, entité non persistée), la fin d'essai
     * moins la durée configurée — approximation, mais du bon ordre de grandeur.
     */
    private LocalDate trialStart(Subscription subscription) {
        LocalDate fromCreation = toUtcDate(subscription.getCreatedAt());
        if (fromCreation != null) {
            return fromCreation;
        }
        LocalDate trialEnd = toUtcDate(subscription.getTrialEndsAt());
        return trialEnd == null ? null : trialEnd.minusDays(billingProperties.trialDays());
    }

    /**
     * Fin de la fenêtre d'essai telle qu'elle est présentée : la fin d'essai enregistrée, ou, faute
     * de date, le premier du mois suivant — on n'invente pas une échéance qui n'existe pas.
     */
    private LocalDate trialEndDate(Subscription subscription, LocalDate currentPeriodStart) {
        LocalDate trialEnd = toUtcDate(subscription.getTrialEndsAt());
        return trialEnd == null ? currentPeriodStart.plusMonths(1) : trialEnd;
    }

    private static LocalDate toUtcDate(OffsetDateTime instant) {
        return instant == null ? null : instant.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
    }

    /** Premier jour du mois calendaire courant (UTC) — même définition de période que F-10. */
    private LocalDate currentPeriodStart() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
    }

    /** Report d'une fenêtre : ce que les mois déjà clos ont consommé, traité et crédité. */
    private record QuotaCarryOver(long billedTokens, long processedTokens, long bonusTokens) {

        private static final QuotaCarryOver NONE = new QuotaCarryOver(0L, 0L, 0L);
    }
}
