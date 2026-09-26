package fr.claudegateway.diagnostic;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.quota.ProviderPricingProperties;
import fr.claudegateway.quota.UsageTurn;
import fr.claudegateway.quota.UsageTurnRepository;
import fr.claudegateway.runner.audit.RunnerAudit;
import fr.claudegateway.runner.audit.RunnerAuditRepository;

/**
 * <b>L'enquête sur une période</b> (F-156 / SF-156-02) : ce que chaque capacité de la carte a fait,
 * sur <b>tous les projets</b> du compte, et ce que le gaspillage a coûté.
 *
 * <p><b>Pourquoi l'accumulation.</b> Un motif vu une fois est une anecdote ; vu sur une semaine et
 * huit projets, il est structurel. Le gain se chiffre alors en <b>euros par semaine</b>, et c'est
 * ce qui rend le seuil d'impact vérifiable plutôt que déclaratif.</p>
 *
 * <p><b>On ne calcule un montant que quand il se calcule.</b> Pour les capacités dont le
 * gaspillage ne se déduit pas des mesures existantes, l'observation porte la fréquence et attend
 * le verdict de SF-156-03. Un montant inventé est exactement ce que le seuil interdit.</p>
 */
@Service
public class ProductSurveyService {

    /** Au-delà, ce n'est plus une enquête, c'est un export. */
    static final Duration MAX_PERIOD = Duration.ofDays(31);

    /** La part de cache visée, la même que le bilan de session — une seule cible, pas deux. */
    static final int TARGET_CACHE_SHARE = 90;

    private final UsageTurnRepository usageTurns;
    private final RunnerAuditRepository runnerAudit;
    private final ProviderPricingProperties pricing;

    public ProductSurveyService(UsageTurnRepository usageTurns, RunnerAuditRepository runnerAudit,
                                ProviderPricingProperties pricing) {
        this.usageTurns = usageTurns;
        this.runnerAudit = runnerAudit;
        this.pricing = pricing;
    }

    /**
     * Ce que la période dit du produit.
     *
     * <p>Une période inversée ou incomplète ne lit <b>rien</b> ; une période trop longue est
     * ramenée à la borne, et le résultat le dit — sinon le dénominateur mentirait.</p>
     */
    @Transactional(readOnly = true)
    public ProductSurvey survey(UUID userId, OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null || !to.isAfter(from)) {
            return ProductSurvey.empty(from, to);
        }
        boolean truncated = Duration.between(from, to).compareTo(MAX_PERIOD) > 0;
        OffsetDateTime start = truncated ? to.minus(MAX_PERIOD) : from;

        List<UsageTurn> turns = usageTurns
                .findByUserIdAndOccurredAtBetweenOrderByOccurredAtAsc(userId, start, to);
        List<RunnerAudit> calls = runnerAudit
                .findByUserIdAndCreatedAtBetweenOrderByCreatedAtAsc(userId, start, to);
        if (turns.isEmpty() && calls.isEmpty()) {
            return ProductSurvey.empty(start, to);
        }

        Set<UUID> projects = new HashSet<>();
        turns.forEach(t -> add(projects, t.getWorkspaceId()));
        calls.forEach(c -> add(projects, c.getWorkspaceId()));

        BigDecimal costEur = eur(turns.stream()
                .map(UsageTurn::getProviderCostUsd)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        List<CapabilityObservation> observations = new ArrayList<>();
        for (ProductCapability capability : CapabilityMap.capabilities()) {
            observations.add(observe(capability, turns, calls, projects.size()));
        }

        return new ProductSurvey(start, to, truncated, turns.size(), projects.size(), costEur,
                List.copyOf(observations));
    }

    /** Ce que la période dit d'une capacité, signal par signal. */
    private CapabilityObservation observe(ProductCapability capability, List<UsageTurn> turns,
                                          List<RunnerAudit> calls, int projectsObserved) {
        long hits = 0;
        Set<UUID> withSignal = new HashSet<>();
        boolean measurable = false;
        BigDecimal waste = null;

        for (ProductCapability.Signal signal : capability.signals()) {
            switch (signal.kind()) {
                case TOOL -> {
                    measurable = true;
                    for (RunnerAudit call : calls) {
                        if (signal.value().equals(call.getTool())) {
                            hits++;
                            add(withSignal, call.getWorkspaceId());
                        }
                    }
                }
                case USAGE -> {
                    measurable = true;
                    for (UsageTurn turn : turns) {
                        if (usageHit(signal.value(), turn)) {
                            hits++;
                            add(withSignal, turn.getWorkspaceId());
                        }
                    }
                    if ("cache_read_tokens".equals(signal.value())) {
                        waste = coldCacheWaste(turns);
                    }
                }
                // Les signaux de TABLE demandent de lire une table que cette enquête ne connaît
                // pas : le verdict attend SF-156-03, qui saura le faire. On ne devine pas.
                case TABLE -> { }
            }
        }

        return new CapabilityObservation(capability.id(), capability.name(), hits,
                withSignal.size(), projectsObserved, waste, measurable);
    }

    /** Une grandeur d'usage compte comme un déclenchement quand elle est <b>non nulle</b>. */
    private static boolean usageHit(String column, UsageTurn turn) {
        return switch (column) {
            case "cache_read_tokens" -> turn.getCacheReadTokens() > 0;
            case "cache_write_tokens" -> turn.getCacheWriteTokens() > 0;
            case "input_tokens" -> turn.getInputTokens() > 0;
            case "output_tokens" -> turn.getOutputTokens() > 0;
            default -> false;
        };
    }

    /**
     * Ce que le cache froid a coûté sur la période, sur la <b>grille réelle</b> du modèle de chaque
     * tour — un tour d'Opus coûte cinq fois un tour de Haiku, et un tarif moyen se tromperait dès
     * qu'un chemin change de modèle.
     */
    private BigDecimal coldCacheWaste(List<UsageTurn> turns) {
        BigDecimal usd = BigDecimal.ZERO;
        for (UsageTurn turn : turns) {
            // SF-155-06 : `input_tokens` contient DÉJÀ le cache lu — l'additionner comptait le
            // cache deux fois et faisait paraître froid un cache sain.
            long total = turn.getInputTokens();
            if (total == 0) {
                continue;
            }
            int share = fr.claudegateway.bilan.CacheShare.of(total, turn.getCacheReadTokens());
            if (share >= TARGET_CACHE_SHARE) {
                continue;
            }
            ProviderPricingProperties.ModelPricing grid = turn.getModel() == null
                    ? pricing.fallbackPricing() : pricing.pricingOf(turn.getModel());
            BigDecimal perToken = grid.input().subtract(grid.cacheRead());
            if (perToken.signum() <= 0) {
                continue;
            }
            long movable = Math.round(total * (TARGET_CACHE_SHARE - share) / 100.0);
            usd = usd.add(perToken.multiply(BigDecimal.valueOf(movable))
                    .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP));
        }
        return eur(usd);
    }

    private BigDecimal eur(BigDecimal usd) {
        return usd.multiply(pricing.usdToEur()).setScale(2, RoundingMode.HALF_UP);
    }

    private static void add(Set<UUID> set, UUID value) {
        if (value != null) {
            set.add(value);
        }
    }
}
