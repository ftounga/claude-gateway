package fr.claudegateway.quota;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.admin.AdminService;

/**
 * Les deux alertes de dépense (F-133 / SF-133-06) : on approche du budget de la semaine, ou on l'a
 * dépassé.
 *
 * <p><b>Tout est calculé à la lecture</b> — rien n'est stocké, rien n'est « marqué comme vu ». Une
 * alerte est donc toujours juste, et il n'y a pas d'état à resynchroniser.</p>
 *
 * <p><b>Le total se compare à la somme des budgets applicables</b>, et non au budget par défaut :
 * celui-ci vaut <b>par client</b> (SF-133-04), et en faire aussi un plafond global le ferait dire
 * deux choses à la fois.</p>
 *
 * <p><b>Aucun rapport avec l'alerte de quota (F-42)</b>, qui prévient l'utilisateur que son quota
 * commercial mensuel s'épuise et lui propose une recharge. Ici, c'est l'administrateur qu'on
 * prévient, sur une dépense hebdomadaire qu'il a lui-même budgétée, et il n'y a rien à racheter.</p>
 */
@Service
public class CostAlertService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100L);

    private final HostCostService hostCostService;
    private final CostBudgetService budgetService;
    private final TurnCostView costView;
    private final CostAlertProperties properties;
    private final AdminService adminService;
    private final Clock clock;

    public CostAlertService(HostCostService hostCostService, CostBudgetService budgetService,
            TurnCostView costView, CostAlertProperties properties, AdminService adminService,
            Clock clock) {
        this.hostCostService = hostCostService;
        this.budgetService = budgetService;
        this.costView = costView;
        this.properties = properties;
        this.adminService = adminService;
        this.clock = clock;
    }

    /**
     * Les alertes en cours pour la semaine courante de l'utilisateur.
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     */
    @Transactional(readOnly = true)
    public List<CostAlert> currentWeek(UUID userId) {
        adminService.assertAdmin();
        CostWindow week = CostWindow.currentWeek(clock);
        HostCost costs = hostCostService.costs(userId, week);

        List<CostAlert> alerts = new ArrayList<>();
        BigDecimal budgetedTotal = BigDecimal.ZERO;
        BigDecimal spentOnBudgeted = BigDecimal.ZERO;

        for (HostCost.Client client : costs.clients()) {
            // Le seau « hors client » n'a pas de budget : il n'est pas un client.
            if (client.hostId() == null) {
                continue;
            }
            Optional<BigDecimal> budget = budgetService.budgetOf(userId, client.hostId());
            if (budget.isEmpty()) {
                // On ne peut pas dépasser un budget qui n'existe pas.
                continue;
            }
            BigDecimal spent = costView.toEur(client.costUsd());
            budgetedTotal = budgetedTotal.add(budget.get());
            spentOnBudgeted = spentOnBudgeted.add(spent);
            alertFor(CostAlert.Scope.HOST, client.hostId(), client.hostName(), spent, budget.get(),
                    week).ifPresent(alerts::add);
        }

        if (budgetedTotal.signum() > 0 || spentOnBudgeted.signum() > 0) {
            alertFor(CostAlert.Scope.TOTAL, null, null, spentOnBudgeted, budgetedTotal, week)
                    .ifPresent(alerts::add);
        }
        return List.copyOf(alerts);
    }

    /**
     * L'alerte d'une ligne, s'il y a lieu.
     *
     * <p>Les deux niveaux s'excluent : au-delà du budget, c'est <b>dépassé</b> qui est dit, pas
     * « on approche » — l'un remplace l'autre, il ne s'y ajoute pas.</p>
     */
    private Optional<CostAlert> alertFor(CostAlert.Scope scope, UUID hostId, String hostName,
            BigDecimal spent, BigDecimal budget, CostWindow week) {
        if (budget.signum() == 0) {
            // Budget à zéro : toute dépense est un dépassement, et l'absence de dépense n'est rien.
            return spent.signum() > 0
                    ? Optional.of(new CostAlert(scope, hostId, hostName, spent, budget, 100,
                            CostAlert.Level.EXCEEDED, week.firstDay()))
                    : Optional.empty();
        }
        BigDecimal ratio = spent.divide(budget, 4, RoundingMode.HALF_UP);
        int percent = ratio.multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP).intValue();
        if (ratio.compareTo(BigDecimal.ONE) >= 0) {
            return Optional.of(new CostAlert(scope, hostId, hostName, spent, budget, percent,
                    CostAlert.Level.EXCEEDED, week.firstDay()));
        }
        if (ratio.doubleValue() >= properties.nearThreshold()) {
            return Optional.of(new CostAlert(scope, hostId, hostName, spent, budget, percent,
                    CostAlert.Level.NEAR, week.firstDay()));
        }
        return Optional.empty();
    }
}
