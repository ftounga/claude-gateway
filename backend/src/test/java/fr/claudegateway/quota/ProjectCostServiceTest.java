package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.admin.AdminForbiddenException;
import fr.claudegateway.admin.AdminService;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.quota.dto.ProjectCostView;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * Ce que chaque projet a coûté (F-143 / SF-143-01).
 *
 * <p>Ce qui s'y joue : <b>deux montants</b> et non un — un projet à 2 € cette semaine peut en avoir
 * coûté 300 depuis mars —, un projet sans dépense rendu <b>à zéro</b> et non omis, et la réserve à
 * l'administration, comme partout dans F-133.</p>
 */
class ProjectCostServiceTest {

    private final UsageTurnRepository turns = mock(UsageTurnRepository.class);
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final RunnerHostRepository hosts = mock(RunnerHostRepository.class);
    private final AdminService admin = mock(AdminService.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID projectA = UUID.randomUUID();
    private final UUID projectB = UUID.randomUUID();

    private ProjectCostService service;

    @BeforeEach
    void setUp() {
        service = new ProjectCostService(turns, workspaces, hosts,
                new TurnCostView(mock(AdminService.class),
                        // Taux de 1 pour lire les euros comme les dollars dans les assertions.
                        new ProviderPricingProperties(null, null, null, null, null, BigDecimal.ONE)),
                admin, Clock.fixed(Instant.parse("2026-09-22T10:00:00Z"), ZoneOffset.UTC));
        when(hosts.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(
                RunnerHost.builder().id(hostId).userId(userId).name("CAGIP").build()));
        when(workspaces.list(userId)).thenReturn(List.of(
                workspace(projectA, "migration-bastion"), workspace(projectB, "audit-reseau")));
    }

    private Workspace workspace(UUID id, String name) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        workspace.setUserId(userId);
        workspace.setName(name);
        workspace.setHostId(hostId);
        return workspace;
    }

    /**
     * Une ligne d'agrégat, en dur plutôt qu'en doublure : un {@code mock} construit à l'intérieur
     * d'un {@code thenReturn} imbrique deux stubbings et Mockito le refuse — et une implémentation
     * se lit mieux qu'une pile de {@code when}.
     */
    private record Row(UUID getWorkspaceId, BigDecimal getCostUsd, long getTurns)
            implements ProjectCostAggregate {

        @Override
        public UUID getWorkspaceId() {
            return getWorkspaceId;
        }

        @Override
        public BigDecimal getCostUsd() {
            return getCostUsd;
        }

        @Override
        public long getTurns() {
            return getTurns;
        }
    }

    private ProjectCostAggregate row(UUID workspaceId, String costUsd, long turnCount) {
        return new Row(workspaceId, new BigDecimal(costUsd), turnCount);
    }

    @Test
    @DisplayName("LE CRITÈRE : la semaine ET le total, par projet")
    void theWeekAndTheTotalForEachProject() {
        // Un projet calme cette semaine mais lourd depuis l'origine : c'est exactement ce que l'un
        // des deux chiffres seul cacherait.
        when(turns.aggregateCostByProject(eq(userId), any(), any()))
                .thenReturn(List.of(row(projectA, "2.00", 3L)));
        when(turns.aggregateCostByProjectAllTime(userId))
                .thenReturn(List.of(row(projectA, "300.00", 420L), row(projectB, "12.00", 30L)));

        ProjectCostView view = service.describe(userId);

        ProjectCostView.Project a = view.projects().stream()
                .filter(project -> project.id().equals(projectA)).findFirst().orElseThrow();
        assertThat(a.weekEur()).isEqualByComparingTo("2.00");
        assertThat(a.totalEur()).isEqualByComparingTo("300.00");
        assertThat(a.weekTurns()).isEqualTo(3L);
        assertThat(a.totalTurns()).isEqualTo(420L);
        assertThat(a.hostName()).isEqualTo("CAGIP");
    }

    @Test
    @DisplayName("un projet sans dépense est rendu à ZÉRO, jamais omis")
    void aprojectWithoutSpendIsZeroNotAbsent() {
        // « Ce projet n'a rien coûté » est une information ; son absence n'en est pas une.
        when(turns.aggregateCostByProject(eq(userId), any(), any())).thenReturn(List.of());
        when(turns.aggregateCostByProjectAllTime(userId)).thenReturn(List.of());

        ProjectCostView view = service.describe(userId);

        assertThat(view.projects()).hasSize(2);
        assertThat(view.projects()).allSatisfy(project -> {
            assertThat(project.weekEur()).isEqualByComparingTo("0");
            assertThat(project.totalEur()).isEqualByComparingTo("0");
        });
    }

    @Test
    @DisplayName("les plus coûteux d'abord : c'est l'ordre dans lequel on arbitre")
    void thecostliestComeFirst() {
        when(turns.aggregateCostByProject(eq(userId), any(), any())).thenReturn(List.of());
        when(turns.aggregateCostByProjectAllTime(userId))
                .thenReturn(List.of(row(projectA, "12.00", 3L), row(projectB, "300.00", 40L)));

        assertThat(service.describe(userId).projects().get(0).id()).isEqualTo(projectB);
    }

    @Test
    @DisplayName("la fenêtre est la semaine en cours, pas une approximation")
    void thewindowIsTheCurrentWeek() {
        when(turns.aggregateCostByProject(eq(userId), any(), any())).thenReturn(List.of());
        when(turns.aggregateCostByProjectAllTime(userId)).thenReturn(List.of());

        ProjectCostView view = service.describe(userId);

        // Lundi 21 → dimanche 27 septembre 2026, comme CostWindow la définit pour tout F-133.
        assertThat(view.from()).isEqualTo(java.time.LocalDate.of(2026, 9, 21));
        assertThat(view.to()).isEqualTo(java.time.LocalDate.of(2026, 9, 27));
    }

    @Test
    @DisplayName("réservé à l'administration, comme tout F-133")
    void reservedToAdmins() {
        doThrow(new AdminForbiddenException()).when(admin).assertAdmin();

        assertThatThrownBy(() -> service.describe(userId))
                .isInstanceOf(AdminForbiddenException.class);
    }
}
