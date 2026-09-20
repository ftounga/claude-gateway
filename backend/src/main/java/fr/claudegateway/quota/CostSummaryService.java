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
import fr.claudegateway.runner.host.HostMissionStatus;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * L'écran du coût réel, en une lecture (F-133 / SF-133-07) : dépense, budget, part — par client et
 * au total.
 *
 * <p><b>Il n'invente aucune règle</b> : la dépense vient de {@link HostCostService}, le budget de
 * {@link CostBudgetService}. Il les assemble, et c'est tout. Une part calculée ici <b>et</b> dans
 * les alertes finirait par donner deux chiffres différents sur le même écran.</p>
 *
 * <p><b>Tous les clients apparaissent</b>, qu'ils aient dépensé ou non (F-133 / SF-133-13). C'est
 * au moment où un client n'a <b>pas encore</b> dépensé qu'on veut lui poser un plafond : la liste
 * ne peut donc pas venir de la seule dépense. Un client sans dépense est écrit à zéro, avec son
 * nom, et son champ « Budget ».</p>
 */
@Service
public class CostSummaryService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100L);

    private final HostCostService hostCostService;
    private final CostBudgetService budgetService;
    private final CostBudgetRepository budgetRepository;
    private final RunnerHostRepository hostRepository;
    private final TurnCostView costView;
    private final AdminService adminService;
    private final Clock clock;

    public CostSummaryService(HostCostService hostCostService, CostBudgetService budgetService,
            CostBudgetRepository budgetRepository, RunnerHostRepository hostRepository,
            TurnCostView costView, AdminService adminService, Clock clock) {
        this.hostCostService = hostCostService;
        this.budgetService = budgetService;
        this.budgetRepository = budgetRepository;
        this.hostRepository = hostRepository;
        this.costView = costView;
        this.adminService = adminService;
        this.clock = clock;
    }

    /**
     * La synthèse de la période.
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     * @param period {@code week} ou {@code month} ; toute autre valeur est refusée
     */
    @Transactional(readOnly = true)
    public CostSummary summary(UUID userId, String period) {
        adminService.assertAdmin();
        CostWindow window = windowOf(period);
        HostCost costs = hostCostService.costs(userId, window);

        List<CostSummary.Client> clients = new ArrayList<>();
        BigDecimal spentTotal = BigDecimal.ZERO;
        BigDecimal budgetTotal = BigDecimal.ZERO;
        boolean anyBudget = false;
        List<UUID> seen = new ArrayList<>();

        for (HostCost.Client client : costs.clients()) {
            BigDecimal spent = costView.toEur(client.costUsd());
            spentTotal = spentTotal.add(spent);
            // Le seau « hors client » n'a pas de budget : il n'est pas un client.
            Optional<BigDecimal> budget = client.hostId() == null
                    ? Optional.empty()
                    : budgetService.budgetOf(userId, client.hostId());
            if (budget.isPresent()) {
                anyBudget = true;
                budgetTotal = budgetTotal.add(budget.get());
            }
            if (client.hostId() != null) {
                seen.add(client.hostId());
            }
            clients.add(new CostSummary.Client(client.hostId(), client.hostName(), spent,
                    budget.orElse(null), percentOf(spent, budget.orElse(null)),
                    hasOwnBudget(userId, client.hostId()), client.totalTokens()));
        }

        // Puis TOUS LES AUTRES CLIENTS, à zéro (SF-133-13). Le budget est une décision d'avance :
        // tant que la liste venait de la seule dépense, on ne pouvait poser un plafond qu'APRÈS
        // coup — et un client budgété sans dépense n'était visible qu'une fois budgété. Les
        // clôturés sont exclus : on ne budgète pas une mission terminée.
        for (RunnerHost host : hostRepository
                .findByUserIdAndMissionStatusNotOrderByCreatedAtAsc(userId, HostMissionStatus.CLOSED)) {
            if (seen.contains(host.getId())) {
                continue;
            }
            seen.add(host.getId());
            Optional<BigDecimal> budget = budgetService.budgetOf(userId, host.getId());
            if (budget.isPresent()) {
                anyBudget = true;
                budgetTotal = budgetTotal.add(budget.get());
            }
            clients.add(new CostSummary.Client(host.getId(), host.getName(), BigDecimal.ZERO,
                    budget.orElse(null), percentOf(BigDecimal.ZERO, budget.orElse(null)),
                    hasOwnBudget(userId, host.getId()), 0L));
        }

        // Enfin, un budget posé sur un poste qui n'existe plus : il se voit, donc il se retire.
        for (CostBudget budget : budgetRepository.findByUserId(userId)) {
            if (budget.getHostId() == null || seen.contains(budget.getHostId())) {
                continue;
            }
            anyBudget = true;
            budgetTotal = budgetTotal.add(budget.getAmountEur());
            clients.add(new CostSummary.Client(budget.getHostId(), null, BigDecimal.ZERO,
                    budget.getAmountEur(), percentOf(BigDecimal.ZERO, budget.getAmountEur()), true,
                    0L));
        }

        BigDecimal totalBudget = anyBudget ? budgetTotal : null;
        return new CostSummary(period, window.firstDay(), window.lastDay(),
                spentTotal, totalBudget, percentOf(spentTotal, totalBudget), List.copyOf(clients));
    }

    /** {@code null} sans budget : l'écran n'affiche alors aucune part, plutôt qu'une part inventée. */
    private static Integer percentOf(BigDecimal spent, BigDecimal budget) {
        if (budget == null) {
            return null;
        }
        if (budget.signum() == 0) {
            // Budget à zéro : toute dépense est un dépassement total, l'absence de dépense est 0 %.
            return spent.signum() > 0 ? 100 : 0;
        }
        return spent.divide(budget, 4, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    }

    private boolean hasOwnBudget(UUID userId, UUID hostId) {
        return hostId != null && budgetRepository.findByUserIdAndHostId(userId, hostId).isPresent();
    }

    private CostWindow windowOf(String period) {
        return switch (period == null ? "" : period.trim().toLowerCase()) {
            case "week" -> CostWindow.currentWeek(clock);
            case "month" -> CostWindow.currentMonth(clock);
            default -> throw new InvalidUsageWindowException(
                    "Période inconnue : attendu « week » ou « month ».");
        };
    }
}
