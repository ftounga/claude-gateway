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

    /** Échelle de division tokens → millions (coût = tokens/1e6 × prix_par_million). */
    private static final int TOKENS_PER_MILLION = 1_000_000;
    /** Précision monétaire du coût estimé (montants unitaires potentiellement faibles). */
    private static final int COST_SCALE = 4;

    private final UsageCounterRepository usageCounterRepository;
    private final UsageReportProperties properties;
    private final Clock clock;

    public UsageReportService(
            UsageCounterRepository usageCounterRepository,
            UsageReportProperties properties,
            Clock clock) {
        this.usageCounterRepository = usageCounterRepository;
        this.properties = properties;
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
     * Coût estimé d'une période = tokens d'entrée/1e6 × prix_entrée + tokens de sortie/1e6 ×
     * prix_sortie, arrondi à {@value #COST_SCALE} décimales (HALF_UP).
     */
    private BigDecimal estimateCost(long inputTokens, long outputTokens) {
        BigDecimal million = BigDecimal.valueOf(TOKENS_PER_MILLION);
        BigDecimal inputCost = BigDecimal.valueOf(inputTokens)
                .multiply(properties.inputCostPerMillionTokens())
                .divide(million, COST_SCALE, RoundingMode.HALF_UP);
        BigDecimal outputCost = BigDecimal.valueOf(outputTokens)
                .multiply(properties.outputCostPerMillionTokens())
                .divide(million, COST_SCALE, RoundingMode.HALF_UP);
        return inputCost.add(outputCost);
    }

    /** Premier jour du mois calendaire courant (UTC). */
    private LocalDate currentPeriodStart() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
    }
}
