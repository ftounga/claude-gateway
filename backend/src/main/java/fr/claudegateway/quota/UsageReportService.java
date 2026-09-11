package fr.claudegateway.quota;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Construit le rapport d'usage &amp; coût d'un utilisateur (F-16). Agrège en lecture seule les
 * compteurs F-10 (`usage_counters`) et applique un tarif estimé configuré.
 *
 * <p>Point d'isolation : le {@code userId} provient toujours du contexte de sécurité (jamais d'un
 * paramètre client) et filtre tous les accès. Aucune donnée sensible n'est exposée. Traitement
 * purement relationnel (Gateway-First) : aucun appel IA, aucun traitement lourd.</p>
 */
@Service
public class UsageReportService {

    /** Précision monétaire du coût estimé (montants unitaires potentiellement faibles). */
    private static final int COST_SCALE = UsageCostEstimator.COST_SCALE;

    private final UsageCounterRepository usageCounterRepository;
    private final UsageReportProperties properties;
    private final UsageCostEstimator costEstimator;
    private final Clock clock;

    public UsageReportService(
            UsageCounterRepository usageCounterRepository,
            UsageReportProperties properties,
            UsageCostEstimator costEstimator,
            Clock clock) {
        this.usageCounterRepository = usageCounterRepository;
        this.properties = properties;
        this.costEstimator = costEstimator;
        this.clock = clock;
    }

    /**
     * Rapport d'usage &amp; coût de l'utilisateur pour la fenêtre des {@code max-months} derniers mois.
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     * @return historique mensuel (plus récent d'abord) + totaux ; périodes vides si aucun compteur
     */
    @Transactional(readOnly = true)
    public UsageReport buildReport(UUID userId) {
        LocalDate currentPeriod = currentPeriodStart();

        List<UsagePeriod> periods = new ArrayList<>();
        long totalInput = 0L;
        long totalOutput = 0L;
        BigDecimal totalCost = BigDecimal.ZERO;

        List<UsageCounter> counters = usageCounterRepository
                .findByUserIdOrderByPeriodStartDesc(userId)
                .stream()
                .limit(properties.maxMonths())
                .toList();

        for (UsageCounter counter : counters) {
            long input = counter.getInputTokens();
            long output = counter.getOutputTokens();
            BigDecimal cost = estimateCost(input, output);
            LocalDate periodStart = counter.getPeriodStart();

            periods.add(new UsagePeriod(
                    periodStart,
                    periodStart.plusMonths(1),
                    input,
                    output,
                    input + output,
                    cost,
                    periodStart.equals(currentPeriod)));

            totalInput += input;
            totalOutput += output;
            totalCost = totalCost.add(cost);
        }

        return new UsageReport(
                properties.currency(),
                periods,
                totalInput,
                totalOutput,
                totalInput + totalOutput,
                totalCost.setScale(COST_SCALE, RoundingMode.HALF_UP));
    }

    /**
     * Coût estimé d'une période, délégué à l'estimateur commun (F-61) : le rapport mensuel, la
     * consommation par client et la console d'administration doivent annoncer <b>le même</b>
     * montant pour la même consommation.
     */
    private BigDecimal estimateCost(long inputTokens, long outputTokens) {
        return costEstimator.estimate(inputTokens, outputTokens);
    }

    /** Premier jour du mois calendaire courant (UTC). */
    private LocalDate currentPeriodStart() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
    }
}
