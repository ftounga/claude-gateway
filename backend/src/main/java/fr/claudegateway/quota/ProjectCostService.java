package fr.claudegateway.quota;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.quota.dto.ProjectCostView;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * <b>Ce que chaque projet a coûté</b> (F-143 / SF-143-01).
 *
 * <p>Le grain qui manquait. F-133 sait ce qu'un <b>client</b> coûte (SF-133-03) et ce qu'une
 * <b>réponse</b> coûte (SF-133-02) ; entre les deux, le <b>projet</b> — celui qu'on ouvre, celui
 * qu'on arbitre — n'était agrégé qu'en jetons.</p>
 *
 * <p><b>Deux montants, et pas un</b> : la semaine dit ce qui se passe maintenant, le total dit ce
 * que le projet a fini par coûter. Un projet à 2 € cette semaine peut en avoir coûté 300 depuis
 * mars ; l'un sans l'autre ne permet pas de décider.</p>
 *
 * <p><b>Il n'invente aucune règle</b> : la conversion en euros vient de {@link TurnCostView}, la
 * fenêtre de {@link CostWindow}. Deux définitions de la semaine, ou deux taux de change, finiraient
 * par afficher deux chiffres du même fait.</p>
 *
 * <p><b>Un projet sans dépense est rendu à zéro</b>, jamais omis : « ce projet n'a rien coûté » est
 * une information, son absence n'en est pas une.</p>
 */
@Service
public class ProjectCostService {

    private final UsageTurnRepository turns;
    private final WorkspaceService workspaceService;
    private final RunnerHostRepository hosts;
    private final TurnCostView costView;
    private final AdminService adminService;
    private final Clock clock;

    public ProjectCostService(UsageTurnRepository turns, WorkspaceService workspaceService,
            RunnerHostRepository hosts, TurnCostView costView, AdminService adminService,
            Clock clock) {
        this.turns = turns;
        this.workspaceService = workspaceService;
        this.hosts = hosts;
        this.costView = costView;
        this.adminService = adminService;
        this.clock = clock;
    }

    /** La dépense de chaque projet : la semaine en cours, et le total depuis l'origine. */
    @Transactional(readOnly = true)
    public ProjectCostView describe(UUID userId) {
        adminService.assertAdmin();
        CostWindow week = CostWindow.currentWeek(clock);

        Map<UUID, ProjectCostAggregate> ofWeek = byProject(
                turns.aggregateCostByProject(userId, week.start(), week.end()));
        Map<UUID, ProjectCostAggregate> ofAllTime = byProject(
                turns.aggregateCostByProjectAllTime(userId));

        // Les noms viennent de `WorkspaceService`, déjà borné au propriétaire : aucun identifiant de
        // projet fourni par l'appelant n'intervient ici.
        Map<UUID, String> hostNames = hosts.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .collect(Collectors.toMap(RunnerHost::getId, RunnerHost::getName,
                        (first, second) -> first));

        List<ProjectCostView.Project> projects = new ArrayList<>();
        for (Workspace workspace : workspaceService.list(userId)) {
            ProjectCostAggregate week1 = ofWeek.get(workspace.getId());
            ProjectCostAggregate all = ofAllTime.get(workspace.getId());
            projects.add(new ProjectCostView.Project(
                    workspace.getId(),
                    workspace.getName(),
                    workspace.getHostId(),
                    workspace.getHostId() == null ? null : hostNames.get(workspace.getHostId()),
                    eurOf(week1),
                    eurOf(all),
                    week1 == null ? 0L : week1.getTurns(),
                    all == null ? 0L : all.getTurns()));
        }
        // Les plus coûteux d'abord : c'est l'ordre dans lequel on arbitre.
        projects.sort(Comparator.comparing(ProjectCostView.Project::totalEur).reversed()
                .thenComparing(ProjectCostView.Project::name,
                        Comparator.nullsLast(String::compareToIgnoreCase)));

        return new ProjectCostView(week.firstDay(), week.lastDay(), List.copyOf(projects));
    }

    private static Map<UUID, ProjectCostAggregate> byProject(List<ProjectCostAggregate> rows) {
        Map<UUID, ProjectCostAggregate> byId = new HashMap<>();
        for (ProjectCostAggregate row : rows) {
            if (row.getWorkspaceId() != null) {
                byId.put(row.getWorkspaceId(), row);
            }
        }
        return byId;
    }

    /** Conversion déléguée : un second taux de change finirait par contredire le premier. */
    private BigDecimal eurOf(ProjectCostAggregate aggregate) {
        if (aggregate == null || aggregate.getCostUsd() == null) {
            return BigDecimal.ZERO;
        }
        return costView.toEur(aggregate.getCostUsd());
    }

    /** Pour les tests : la fonction de conversion telle qu'employée ici. */
    Function<BigDecimal, BigDecimal> conversion() {
        return costView::toEur;
    }
}
