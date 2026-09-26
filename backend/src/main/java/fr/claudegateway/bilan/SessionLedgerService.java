package fr.claudegateway.bilan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.quota.ProviderPricingProperties;
import fr.claudegateway.quota.UsageTurn;
import fr.claudegateway.quota.UsageTurnRepository;
import fr.claudegateway.runner.audit.RunnerAudit;
import fr.claudegateway.runner.audit.RunnerAuditRepository;

/**
 * <b>L'agrégation d'une session</b> (F-155 / SF-155-01) : ce qui a été fait, ce que ça a coûté, et
 * où l'argent est parti.
 *
 * <p><b>Aucune migration, aucun appel modèle.</b> Tout est déjà mesuré — {@code usage_turns} (F-133)
 * et {@code runner_audit} (F-38) ; ce qui manquait, c'est le regard en arrière.</p>
 *
 * <p><b>Isolation.</b> {@code requireOwned} <b>en premier</b>, puis deux lectures qui portent
 * {@code user_id} <i>et</i> {@code workspace_id}.</p>
 */
@Service
public class SessionLedgerService {

    /** Trois de chaque : un classement, pas un inventaire. */
    static final int TOP = 3;

    /** Les outils qui écrivent — ce sont eux qui disent ce que la session a <b>produit</b>. */
    private static final Set<String> WRITING_TOOLS = Set.of("write_file", "edit_file", "multi_edit");

    private static final String OUTCOME_OK = "OK";

    private final WorkspaceService workspaceService;
    private final UsageTurnRepository usageTurns;
    private final RunnerAuditRepository runnerAudit;
    private final ProviderPricingProperties pricing;

    public SessionLedgerService(WorkspaceService workspaceService,
                                UsageTurnRepository usageTurns,
                                RunnerAuditRepository runnerAudit,
                                ProviderPricingProperties pricing) {
        this.workspaceService = workspaceService;
        this.usageTurns = usageTurns;
        this.runnerAudit = runnerAudit;
        this.pricing = pricing;
    }

    /**
     * Le relevé d'un projet sur une fenêtre.
     *
     * <p>Une fenêtre inversée ou sans activité rend un relevé <b>vide</b> : une session sans rien
     * dedans n'est pas une erreur.</p>
     */
    @Transactional(readOnly = true)
    public SessionLedger of(UUID userId, UUID workspaceId, OffsetDateTime from, OffsetDateTime to) {
        workspaceService.requireOwned(userId, workspaceId); // 404 si non possédé — TOUJOURS en premier

        if (from == null || to == null || to.isBefore(from)) {
            return SessionLedger.empty(from, to);
        }

        List<UsageTurn> turns = usageTurns
                .findByUserIdAndWorkspaceIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                        userId, workspaceId, from, to);
        List<RunnerAudit> calls = runnerAudit
                .findByUserIdAndWorkspaceIdAndCreatedAtBetweenOrderByCreatedAtAsc(
                        userId, workspaceId, from, to);

        if (turns.isEmpty() && calls.isEmpty()) {
            return SessionLedger.empty(from, to);
        }

        long input = turns.stream().mapToLong(UsageTurn::getInputTokens).sum();
        long output = turns.stream().mapToLong(UsageTurn::getOutputTokens).sum();
        long cacheRead = turns.stream().mapToLong(UsageTurn::getCacheReadTokens).sum();
        long cacheWrite = turns.stream().mapToLong(UsageTurn::getCacheWriteTokens).sum();

        BigDecimal costUsd = turns.stream()
                .map(UsageTurn::getProviderCostUsd)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int withoutCost = (int) turns.stream().filter(t -> t.getProviderCostUsd() == null).count();

        return new SessionLedger(
                from, to,
                turns.size(),
                elapsedOf(turns, calls),
                calls.size(),
                (int) calls.stream().filter(c -> !OUTCOME_OK.equals(c.getOutcome())).count(),
                filesWritten(calls),
                eur(costUsd),
                input, output, cacheRead, cacheWrite,
                cacheShare(input, cacheRead),
                withoutCost,
                dominantModel(turns),
                totalToolTime(calls),
                costliest(turns),
                heaviest(calls));
    }

    /**
     * Le modèle le plus servi de la session : les détecteurs de coût (SF-155-02) ont besoin de
     * <b>sa</b> grille, pas d'un tarif moyen — un tour d'Opus coûte cinq fois un tour de Haiku.
     */
    private static String dominantModel(List<UsageTurn> turns) {
        Map<String, Integer> byModel = new LinkedHashMap<>();
        for (UsageTurn turn : turns) {
            if (turn.getModel() != null) {
                byModel.merge(turn.getModel(), 1, Integer::sum);
            }
        }
        return byModel.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** La durée cumulée de TOUS les appels : sans elle, aucune « part du temps » n'est calculable. */
    private static Duration totalToolTime(List<RunnerAudit> calls) {
        long ms = calls.stream()
                .mapToLong(c -> c.getDurationMs() == null ? 0 : c.getDurationMs())
                .sum();
        return Duration.ofMillis(ms);
    }

    /**
     * Du premier au dernier événement : le temps <b>réel</b> de la session, pas la somme des durées
     * d'outils — celle-ci compterait deux fois ce qui a tourné en parallèle.
     */
    private static Duration elapsedOf(List<UsageTurn> turns, List<RunnerAudit> calls) {
        OffsetDateTime first = null;
        OffsetDateTime last = null;
        for (UsageTurn turn : turns) {
            first = earliest(first, turn.getOccurredAt());
            last = latest(last, turn.getOccurredAt());
        }
        for (RunnerAudit call : calls) {
            first = earliest(first, call.getCreatedAt());
            last = latest(last, call.getCreatedAt());
        }
        return first == null || last == null ? Duration.ZERO : Duration.between(first, last);
    }

    /** Les fichiers <b>distincts</b> écrits : dix écritures du même fichier font un fichier. */
    private static int filesWritten(List<RunnerAudit> calls) {
        Set<String> paths = new HashSet<>();
        for (RunnerAudit call : calls) {
            if (WRITING_TOOLS.contains(call.getTool()) && call.getTarget() != null
                    && OUTCOME_OK.equals(call.getOutcome())) {
                paths.add(call.getTarget());
            }
        }
        return paths.size();
    }

    /**
     * La part du cache dans l'entrée totale, de 0 à 100. C'est le chiffre de F-134 : un jeton lu en
     * cache coûte une fraction d'un jeton plein, et cette part dit à elle seule si un projet paie sa
     * consigne système à chaque tour.
     *
     * <p>Déléguée à {@link CacheShare} depuis SF-155-06 : cette méthode divisait par
     * {@code input + cacheRead} alors que {@code input} <b>contient déjà</b> le cache, et rendait
     * donc la moitié de la vraie part.</p>
     */
    private static int cacheShare(long input, long cacheRead) {
        return CacheShare.of(input, cacheRead);
    }

    private List<SessionLedger.CostlyTurn> costliest(List<UsageTurn> turns) {
        return turns.stream()
                .filter(t -> t.getProviderCostUsd() != null)
                .sorted(Comparator.comparing(UsageTurn::getProviderCostUsd).reversed())
                .limit(TOP)
                .map(t -> new SessionLedger.CostlyTurn(t.getOccurredAt(), t.getModel(),
                        eur(t.getProviderCostUsd()), t.getInputTokens(), t.getOutputTokens(),
                        t.getCacheReadTokens()))
                .toList();
    }

    /**
     * Les outils les plus lourds, par <b>durée cumulée</b> — pas par nombre d'appels : cent lectures
     * instantanées pèsent moins qu'une commande de quatre minutes.
     */
    private static List<SessionLedger.HeavyTool> heaviest(List<RunnerAudit> calls) {
        Map<String, long[]> byTool = new LinkedHashMap<>(); // [appels, millisecondes, échecs]
        for (RunnerAudit call : calls) {
            long[] cell = byTool.computeIfAbsent(call.getTool(), t -> new long[3]);
            cell[0]++;
            cell[1] += call.getDurationMs() == null ? 0 : call.getDurationMs();
            if (!OUTCOME_OK.equals(call.getOutcome())) {
                cell[2]++;
            }
        }
        List<SessionLedger.HeavyTool> tools = new ArrayList<>();
        byTool.forEach((tool, cell) -> tools.add(new SessionLedger.HeavyTool(
                tool, (int) cell[0], Duration.ofMillis(cell[1]), (int) cell[2])));
        tools.sort(Comparator.comparing(SessionLedger.HeavyTool::total).reversed()
                .thenComparing(Comparator.comparingInt(SessionLedger.HeavyTool::calls).reversed()));
        return tools.stream().limit(TOP).toList();
    }

    /** Le taux d'affichage configuré, jamais un taux en dur. */
    private BigDecimal eur(BigDecimal usd) {
        return usd.multiply(pricing.usdToEur()).setScale(2, RoundingMode.HALF_UP);
    }

    private static OffsetDateTime earliest(OffsetDateTime current, OffsetDateTime candidate) {
        return current == null || (candidate != null && candidate.isBefore(current)) ? candidate : current;
    }

    private static OffsetDateTime latest(OffsetDateTime current, OffsetDateTime candidate) {
        return current == null || (candidate != null && candidate.isAfter(current)) ? candidate : current;
    }
}
