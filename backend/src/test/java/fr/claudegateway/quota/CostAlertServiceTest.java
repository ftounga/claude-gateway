package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.admin.AdminService;

/**
 * Les deux alertes de dépense (F-133 / SF-133-06) : quand on approche, quand on dépasse, et surtout
 * quand on ne dit rien.
 */
class CostAlertServiceTest {

    private final HostCostService costs = mock(HostCostService.class);
    private final CostBudgetService budgets = mock(CostBudgetService.class);
    private final TurnCostView costView = new TurnCostView(mock(AdminService.class),
            // Taux à 1 : les montants du test sont directement lisibles en euros.
            new ProviderPricingProperties(null, null, null, null, null, BigDecimal.ONE));
    private final CostAlertService service = new CostAlertService(costs, budgets, costView,
            new CostAlertProperties(null), mock(AdminService.class),
            Clock.fixed(Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC));

    private final UUID user = UUID.randomUUID();
    private final UUID host = UUID.randomUUID();

    @Test
    void saysNothingBelowTheThreshold() {
        given("50.00", "100.00");

        assertThat(service.currentWeek(user)).isEmpty();
    }

    @Test
    void warnsWhenApproaching() {
        given("85.00", "100.00");

        List<CostAlert> alerts = service.currentWeek(user);

        assertThat(alerts).extracting(CostAlert::level)
                .containsOnly(CostAlert.Level.NEAR);
        assertThat(alerts.get(0).percent()).isEqualTo(85);
        // Lundi de la semaine du 16 septembre 2026 (un mercredi).
        assertThat(alerts.get(0).weekStart()).isEqualTo(LocalDate.of(2026, 9, 14));
    }

    @Test
    void warnsOnceWhenExceeded_neverBoth() {
        given("120.00", "100.00");

        List<CostAlert> alerts = service.currentWeek(user);

        // Dépassé REMPLACE « on approche », il ne s'y ajoute pas : deux alertes pour un même fait
        // feraient douter des deux.
        assertThat(alerts).extracting(CostAlert::level)
                .containsOnly(CostAlert.Level.EXCEEDED);
        assertThat(alerts.get(0).percent()).isEqualTo(120);
    }

    @Test
    void saysNothingAboutAClientWithoutABudget() {
        // On ne peut pas dépasser un budget qui n'existe pas — même en dépensant beaucoup.
        when(costs.costs(eq(user), any())).thenReturn(new HostCost(
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20), new BigDecimal("999.00"),
                List.of(new HostCost.Client(host, "poste", new BigDecimal("999.00"), 10L))));
        when(budgets.budgetOf(user, host)).thenReturn(Optional.empty());

        assertThat(service.currentWeek(user)).isEmpty();
    }

    @Test
    void aZeroBudgetIsExceededByAnySpendAndSilentWithoutOne() {
        given("0.01", "0.00");
        assertThat(service.currentWeek(user)).extracting(CostAlert::level)
                .containsOnly(CostAlert.Level.EXCEEDED);

        given("0.00", "0.00");
        assertThat(service.currentWeek(user)).isEmpty();
    }

    @Test
    void theOutsideBucketIsNeverAlerted() {
        // « Hors client » n'est pas un client : il n'a pas de budget, et il n'en prend pas un par
        // défaut.
        when(costs.costs(eq(user), any())).thenReturn(new HostCost(
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20), new BigDecimal("500.00"),
                List.of(new HostCost.Client(null, null, new BigDecimal("500.00"), 10L))));

        assertThat(service.currentWeek(user)).isEmpty();
    }

    @Test
    void anAbsurdThresholdFallsBackToEightyPercent() {
        assertThat(new CostAlertProperties(0d).nearThresholdPercent()).isEqualTo(80);
        assertThat(new CostAlertProperties(-1d).nearThresholdPercent()).isEqualTo(80);
        assertThat(new CostAlertProperties(1d).nearThresholdPercent()).isEqualTo(80);
        assertThat(new CostAlertProperties(null).nearThresholdPercent()).isEqualTo(80);
        assertThat(new CostAlertProperties(0.5d).nearThresholdPercent()).isEqualTo(50);
    }

    @Test
    void theTotalIsComparedToTheSumOfTheApplicableBudgets() {
        // Deux clients sous leur propre budget (60 % et 70 %) : aucun n'alerte seul. Mais leur
        // TOTAL, 130 sur 200, ne dépasse pas non plus. C'est le cas qui vérifie que le total se
        // compare bien à la SOMME des budgets, et non au budget par défaut d'un seul client —
        // 130 € contre 100 € aurait faussement alerté.
        UUID second = UUID.randomUUID();
        when(costs.costs(eq(user), any())).thenReturn(new HostCost(
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20), new BigDecimal("130.00"),
                List.of(new HostCost.Client(host, "poste-a", new BigDecimal("60.00"), 10L),
                        new HostCost.Client(second, "poste-b", new BigDecimal("70.00"), 10L))));
        when(budgets.budgetOf(user, host)).thenReturn(Optional.of(new BigDecimal("100.00")));
        when(budgets.budgetOf(user, second)).thenReturn(Optional.of(new BigDecimal("100.00")));

        assertThat(service.currentWeek(user)).isEmpty();
    }

    @Test
    void theTotalAlertsOnItsOwnWhenTheSumIsExceeded() {
        // Chacun sous son budget à 90 %, mais 180 sur 200 fait 90 % au total : une alerte
        // d'approche par client, et une pour le total. Trois lignes, et c'est voulu — l'admin veut
        // savoir QUI dérape ET où en est l'ensemble.
        UUID second = UUID.randomUUID();
        when(costs.costs(eq(user), any())).thenReturn(new HostCost(
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20), new BigDecimal("180.00"),
                List.of(new HostCost.Client(host, "poste-a", new BigDecimal("90.00"), 10L),
                        new HostCost.Client(second, "poste-b", new BigDecimal("90.00"), 10L))));
        when(budgets.budgetOf(user, host)).thenReturn(Optional.of(new BigDecimal("100.00")));
        when(budgets.budgetOf(user, second)).thenReturn(Optional.of(new BigDecimal("100.00")));

        List<CostAlert> alerts = service.currentWeek(user);

        assertThat(alerts).hasSize(3);
        assertThat(alerts).extracting(CostAlert::scope)
                .containsExactly(CostAlert.Scope.HOST, CostAlert.Scope.HOST,
                        CostAlert.Scope.TOTAL);
        assertThat(alerts.get(2).budgetEur()).isEqualByComparingTo("200.00");
        assertThat(alerts.get(2).percent()).isEqualTo(90);
    }

    /** Un client unique, avec sa dépense et son budget. */
    private void given(String spentEur, String budgetEur) {
        when(costs.costs(eq(user), any())).thenReturn(new HostCost(
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20), new BigDecimal(spentEur),
                List.of(new HostCost.Client(host, "poste-cagip", new BigDecimal(spentEur), 10L))));
        when(budgets.budgetOf(user, host)).thenReturn(Optional.of(new BigDecimal(budgetEur)));
    }
}
