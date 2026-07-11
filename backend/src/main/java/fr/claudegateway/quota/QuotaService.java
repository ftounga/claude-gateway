package fr.claudegateway.quota;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionService;

/**
 * Cœur du contrôle de quota (F-10). Vérifie l'entitlement <b>avant</b> chaque appel au fournisseur
 * et enregistre la consommation <b>après</b>. Point d'isolation : toutes les opérations prennent le
 * {@code userId} du contexte de sécurité (jamais un paramètre client) et filtrent dessus.
 *
 * <p>Période = mois calendaire UTC : le compteur est remis à zéro (nouvelle ligne) à chaque mois.
 * Le pré-contrôle ne réserve pas de tokens (le coût d'un appel est inconnu à l'avance) : il bloque
 * dès que le cumul de la période a atteint le quota.</p>
 */
@Service
public class QuotaService {

    private final UsageCounterRepository usageCounterRepository;
    private final SubscriptionService subscriptionService;
    private final EntitlementService entitlementService;
    private final QuotaProperties quotaProperties;
    private final Clock clock;

    public QuotaService(
            UsageCounterRepository usageCounterRepository,
            SubscriptionService subscriptionService,
            EntitlementService entitlementService,
            QuotaProperties quotaProperties,
            Clock clock) {
        this.usageCounterRepository = usageCounterRepository;
        this.subscriptionService = subscriptionService;
        this.entitlementService = entitlementService;
        this.quotaProperties = quotaProperties;
        this.clock = clock;
    }

    /**
     * Vérifie que l'utilisateur peut encore consommer sur la période courante.
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     * @throws QuotaExceededException si le cumul de la période a atteint le quota de l'entitlement
     */
    @Transactional(readOnly = true)
    public void assertWithinQuota(UUID userId) {
        long quota = effectiveQuota(userId);
        long used = currentPeriodUsage(userId);
        if (used >= quota) {
            throw new QuotaExceededException(
                    "Quota de consommation atteint pour la période courante.");
        }
    }

    /**
     * Ajoute la consommation d'un appel au compteur de la période courante de l'utilisateur.
     * Idempotence structurelle : une seule ligne par ({@code user_id}, période) grâce à la contrainte
     * d'unicité ; une création concurrente est rattrapée par relecture.
     *
     * @param userId       utilisateur authentifié
     * @param inputTokens  tokens d'entrée rapportés par le fournisseur (négatif ignoré → 0)
     * @param outputTokens tokens de sortie rapportés par le fournisseur (négatif ignoré → 0)
     */
    @Transactional
    public void recordUsage(UUID userId, int inputTokens, int outputTokens) {
        long input = Math.max(0, inputTokens);
        long output = Math.max(0, outputTokens);
        if (input == 0 && output == 0) {
            return;
        }
        LocalDate periodStart = currentPeriodStart();
        UsageCounter counter = usageCounterRepository.findByUserIdAndPeriodStart(userId, periodStart)
                .orElseGet(() -> createCounter(userId, periodStart));
        counter.setInputTokens(counter.getInputTokens() + input);
        counter.setOutputTokens(counter.getOutputTokens() + output);
        usageCounterRepository.save(counter);
    }

    /**
     * Instantané de consommation de l'utilisateur pour la période courante (pour {@code GET /usage}).
     *
     * @param userId utilisateur authentifié
     * @return consommation, quota, restant et bornes de la période
     */
    @Transactional(readOnly = true)
    public UsageSnapshot currentUsage(UUID userId) {
        long quota = effectiveQuota(userId);
        long used = currentPeriodUsage(userId);
        long remaining = Math.max(0, quota - used);
        LocalDate periodStart = currentPeriodStart();
        return new UsageSnapshot(used, quota, remaining, periodStart, periodStart.plusMonths(1));
    }

    /**
     * Crédite des tokens rachetés (top-up, F-21) sur la période courante de l'utilisateur : ils
     * s'ajoutent au quota d'abonnement. Appelé depuis le traitement d'un paiement de pack de tokens.
     *
     * @param userId utilisateur bénéficiaire
     * @param tokens nombre de tokens à créditer (négatif/zéro ignoré)
     */
    @Transactional
    public void creditBonusTokens(UUID userId, long tokens) {
        if (tokens <= 0) {
            return;
        }
        LocalDate periodStart = currentPeriodStart();
        UsageCounter counter = usageCounterRepository.findByUserIdAndPeriodStart(userId, periodStart)
                .orElseGet(() -> createCounter(userId, periodStart));
        counter.setBonusTokens(counter.getBonusTokens() + tokens);
        usageCounterRepository.save(counter);
    }

    /**
     * Vérifie que l'utilisateur n'a pas atteint le plafond de temps de bac à sable Managed Agents de
     * la période courante (F-28 / SF-28-12). Pré-contrôle appelé <b>avant</b> toute création de
     * session : un refus n'engage donc aucun coût runtime.
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     * @throws SandboxLimitExceededException si le cumul de la période a atteint le plafond configuré
     */
    @Transactional(readOnly = true)
    public void assertWithinSandboxLimit(UUID userId) {
        long limit = quotaProperties.maxSandboxSeconds();
        long used = currentPeriodSandboxSeconds(userId);
        if (used >= limit) {
            throw new SandboxLimitExceededException(
                    "Plafond de temps de bac à sable atteint pour la période courante.");
        }
    }

    /**
     * Ajoute du temps de bac à sable (secondes {@code active_seconds} d'une session Managed Agents)
     * au compteur de la période courante de l'utilisateur (F-28 / SF-28-12). Même patron d'upsert
     * que {@link #recordUsage} : une seule ligne par ({@code user_id}, période).
     *
     * @param userId  utilisateur authentifié
     * @param seconds secondes de bac à sable à cumuler (négatif/zéro ignoré → aucune écriture)
     */
    @Transactional
    public void recordSandboxSeconds(UUID userId, long seconds) {
        if (seconds <= 0) {
            return;
        }
        LocalDate periodStart = currentPeriodStart();
        UsageCounter counter = usageCounterRepository.findByUserIdAndPeriodStart(userId, periodStart)
                .orElseGet(() -> createCounter(userId, periodStart));
        counter.setSandboxSeconds(counter.getSandboxSeconds() + seconds);
        usageCounterRepository.save(counter);
    }

    /**
     * Temps de bac à sable cumulé de l'utilisateur sur la période courante (0 si aucune ligne).
     *
     * @param userId utilisateur authentifié
     * @return secondes de bac à sable cumulées de la période
     */
    @Transactional(readOnly = true)
    public long currentPeriodSandboxSeconds(UUID userId) {
        return usageCounterRepository.findByUserIdAndPeriodStart(userId, currentPeriodStart())
                .map(UsageCounter::getSandboxSeconds)
                .orElse(0L);
    }

    /** Quota effectif de la période : quota d'abonnement + tokens rachetés (bonus) de la période. */
    private long effectiveQuota(UUID userId) {
        return resolveQuota(userId) + currentPeriodBonus(userId);
    }

    private long currentPeriodBonus(UUID userId) {
        return usageCounterRepository.findByUserIdAndPeriodStart(userId, currentPeriodStart())
                .map(UsageCounter::getBonusTokens)
                .orElse(0L);
    }

    private long resolveQuota(UUID userId) {
        Subscription subscription = subscriptionService.getOrCreateForUser(userId);
        return entitlementService.resolveMonthlyTokenQuota(subscription);
    }

    private long currentPeriodUsage(UUID userId) {
        return usageCounterRepository.findByUserIdAndPeriodStart(userId, currentPeriodStart())
                .map(UsageCounter::totalTokens)
                .orElse(0L);
    }

    private UsageCounter createCounter(UUID userId, LocalDate periodStart) {
        UsageCounter counter = UsageCounter.builder()
                .userId(userId)
                .periodStart(periodStart)
                .inputTokens(0L)
                .outputTokens(0L)
                .build();
        try {
            return usageCounterRepository.save(counter);
        } catch (DataIntegrityViolationException concurrentCreation) {
            // Une écriture concurrente a créé la ligne de période en premier : on la relit.
            return usageCounterRepository.findByUserIdAndPeriodStart(userId, periodStart)
                    .orElseThrow(() -> concurrentCreation);
        }
    }

    /** Premier jour du mois calendaire courant (UTC). */
    private LocalDate currentPeriodStart() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
    }
}
