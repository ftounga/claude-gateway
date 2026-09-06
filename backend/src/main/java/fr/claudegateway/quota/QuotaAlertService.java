package fr.claudegateway.quota;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionService;
import fr.claudegateway.billing.TopUpCatalog;
import fr.claudegateway.billing.TopUpPack;

/**
 * Alerte de consommation (F-42). Prévient l'utilisateur qu'il approche de sa limite — <b>une seule
 * fois par période et par utilisateur</b> — et porte le pack de recharge à proposer.
 *
 * <p><b>Pourquoi « une seule fois » est le vrai sujet.</b> Le calcul du seuil est trivial ; ce qui
 * ne l'est pas, c'est de ne pas répéter l'alerte. Un tour d'agent enchaîne des dizaines d'appels au
 * fournisseur, donc des dizaines de {@code recordUsage} : une alerte recalculée à chaque passage
 * serait émise trente fois, et un utilisateur averti trente fois ne lit plus rien. La marque du
 * « déjà émis » est donc <b>persistée</b>, sur la ligne {@link UsageCounter} de la période — la
 * seule structure qui soit déjà exactement « un utilisateur, une période ». Conséquences :
 * l'unicité survit au redéploiement et reste vraie entre les deux replicas (l'application est
 * stateless), et le mois suivant crée une nouvelle ligne, donc ré-arme l'alerte sans une ligne de
 * code.</p>
 *
 * <p><b>L'alerte n'échoue jamais l'appel.</b> Elle est évaluée après l'incrément de consommation et
 * avant sa sauvegarde, en mutant la ligne déjà en mémoire : aucune écriture supplémentaire. Et
 * l'appelant ({@link QuotaService#recordUsage}) encadre cet appel — une alerte manquée est un défaut
 * d'information, une consommation perdue serait un défaut de facturation.</p>
 *
 * <p>Isolation : toutes les opérations prennent le {@code userId} du contexte de sécurité et lisent
 * la ligne de <b>cet</b> utilisateur. Le seuil est donc calculé par utilisateur, jamais globalement.</p>
 */
@Service
public class QuotaAlertService {

    private static final Logger log = LoggerFactory.getLogger(QuotaAlertService.class);

    private final UsageCounterRepository usageCounterRepository;
    private final SubscriptionService subscriptionService;
    private final EntitlementService entitlementService;
    private final TopUpCatalog topUpCatalog;
    private final QuotaAlertProperties properties;
    private final Clock clock;

    public QuotaAlertService(
            UsageCounterRepository usageCounterRepository,
            SubscriptionService subscriptionService,
            EntitlementService entitlementService,
            TopUpCatalog topUpCatalog,
            QuotaAlertProperties properties,
            Clock clock) {
        this.usageCounterRepository = usageCounterRepository;
        this.subscriptionService = subscriptionService;
        this.entitlementService = entitlementService;
        this.topUpCatalog = topUpCatalog;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Juge le seuil juste après un incrément de consommation et pose la marque d'alerte si — et
     * seulement si — elle n'est pas déjà posée sur cette période.
     *
     * <p>Mute le compteur <b>en mémoire</b> : l'appelant le sauvegarde ensuite, dans la même
     * écriture que la consommation. Ne lève jamais volontairement.</p>
     *
     * @param userId  utilisateur authentifié (contexte de sécurité)
     * @param counter compteur de la période courante, déjà incrémenté et non encore sauvegardé
     */
    public void evaluateAfterUsage(UUID userId, UsageCounter counter) {
        if (counter.getQuotaAlertRaisedAt() != null) {
            // Déjà prévenu sur cette période : c'est ici, et uniquement ici, que se joue l'unicité.
            return;
        }
        long quota = effectiveQuota(userId, counter);
        if (quota <= 0) {
            // Aucun quota plateforme à approcher : offre BYOK (les jetons sont chez le client) ou
            // abonnement qui n'ouvre aucun accès. Prévenir d'un seuil sur zéro n'aurait aucun sens —
            // et pour l'abonnement expiré, ce n'est pas une alerte qu'il faut, c'est le blocage que
            // `assertWithinQuota` pose déjà.
            return;
        }
        double ratio = (double) counter.totalTokens() / (double) quota;
        if (ratio >= properties.threshold()) {
            counter.setQuotaAlertRaisedAt(OffsetDateTime.now(clock));
            log.info("Seuil de consommation franchi pour l'utilisateur {} ({} / {} tokens)",
                    userId, counter.totalTokens(), quota);
        }
    }

    /**
     * Alerte de la période courante de l'utilisateur, telle que l'application doit la présenter.
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     * @return l'alerte ; {@code raised} n'est vrai que si le seuil a été franchi et que l'alerte
     *         n'a pas été écartée
     */
    @Transactional(readOnly = true)
    public QuotaAlert currentAlert(UUID userId) {
        LocalDate periodStart = currentPeriodStart();
        Optional<UsageCounter> counter =
                usageCounterRepository.findByUserIdAndPeriodStart(userId, periodStart);

        long used = counter.map(UsageCounter::totalTokens).orElse(0L);
        long quota = counter.map(c -> effectiveQuota(userId, c))
                .orElseGet(() -> subscriptionQuota(userId));
        boolean raised = counter
                .map(c -> c.getQuotaAlertRaisedAt() != null && c.getQuotaAlertDismissedAt() == null)
                .orElse(false);

        return new QuotaAlert(
                raised,
                used,
                quota,
                Math.max(0L, quota - used),
                percentOf(used, quota),
                properties.thresholdPercent(),
                periodStart.plusMonths(1),
                raised ? recommendedPack().orElse(null) : null);
    }

    /**
     * Écarte l'alerte de la période courante : elle ne sera plus présentée jusqu'au mois suivant,
     * même si la consommation continue de monter.
     *
     * <p>Sans écriture si aucune alerte n'est levée, ou si elle est déjà écartée — écarter deux fois
     * n'écrase pas la première date.</p>
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     */
    @Transactional
    public void dismissCurrentAlert(UUID userId) {
        usageCounterRepository.findByUserIdAndPeriodStart(userId, currentPeriodStart())
                .filter(c -> c.getQuotaAlertRaisedAt() != null)
                .filter(c -> c.getQuotaAlertDismissedAt() == null)
                .ifPresent(counter -> {
                    counter.setQuotaAlertDismissedAt(OffsetDateTime.now(clock));
                    usageCounterRepository.save(counter);
                });
    }

    /** Pack de recharge proposé depuis l'alerte, vide si le code configuré est inconnu du catalogue. */
    private Optional<TopUpPack> recommendedPack() {
        return topUpCatalog.find(properties.topUpPack());
    }

    /** Quota effectif de la période : allocation de l'abonnement + tokens rachetés de la période. */
    private long effectiveQuota(UUID userId, UsageCounter counter) {
        return subscriptionQuota(userId) + counter.getBonusTokens();
    }

    private long subscriptionQuota(UUID userId) {
        Subscription subscription = subscriptionService.getOrCreateForUser(userId);
        return entitlementService.resolveMonthlyTokenQuota(subscription);
    }

    /** Part consommée du quota, en pourcentage entier arrondi (0 si le quota est nul). */
    private static int percentOf(long used, long quota) {
        if (quota <= 0) {
            return 0;
        }
        return (int) Math.round((double) used * 100d / (double) quota);
    }

    /** Premier jour du mois calendaire courant (UTC) — même définition de période que F-10. */
    private LocalDate currentPeriodStart() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
    }
}
