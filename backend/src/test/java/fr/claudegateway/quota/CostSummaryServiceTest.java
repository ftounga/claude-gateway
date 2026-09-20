package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import fr.claudegateway.runner.host.HostMissionStatus;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * La synthèse de l'écran d'administration (F-133 / SF-133-07) : dépense, budget, part.
 */
class CostSummaryServiceTest {

    private final HostCostService costs = mock(HostCostService.class);
    private final CostBudgetService budgets = mock(CostBudgetService.class);
    private final CostBudgetRepository repository = mock(CostBudgetRepository.class);
    private final RunnerHostRepository hosts = mock(RunnerHostRepository.class);
    private final CostSummaryService service = new CostSummaryService(costs, budgets, repository, hosts,
            new TurnCostView(mock(AdminService.class),
                    new ProviderPricingProperties(null, null, null, null, null, BigDecimal.ONE)),
            mock(AdminService.class),
            Clock.fixed(Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC));

    private final UUID user = UUID.randomUUID();
    private final UUID host = UUID.randomUUID();

    @Test
    void joinsSpendAndBudgetIntoOneLine() {
        givenOneClient("40.00");
        when(budgets.budgetOf(user, host)).thenReturn(Optional.of(new BigDecimal("100.00")));

        CostSummary summary = service.summary(user, "week");

        assertThat(summary.spentEur()).isEqualByComparingTo("40.00");
        assertThat(summary.budgetEur()).isEqualByComparingTo("100.00");
        assertThat(summary.percent()).isEqualTo(40);
        assertThat(summary.clients()).hasSize(1);
        assertThat(summary.clients().get(0).percent()).isEqualTo(40);
    }

    @Test
    void showsNoPercentWithoutABudget() {
        // Une part inventée serait pire que pas de part du tout : `null`, et l'écran se tait.
        givenOneClient("40.00");
        when(budgets.budgetOf(user, host)).thenReturn(Optional.empty());

        CostSummary summary = service.summary(user, "week");

        assertThat(summary.clients().get(0).budgetEur()).isNull();
        assertThat(summary.clients().get(0).percent()).isNull();
        assertThat(summary.budgetEur()).isNull();
        assertThat(summary.percent()).isNull();
    }

    @Test
    void aBudgetedClientWithoutSpendStillAppears() {
        // Un budget posé sur un client qui ne travaille pas se voit, et se corrige. L'omettre le
        // rendrait invisible jusqu'à ce qu'il dépense.
        when(costs.costs(eq(user), any())).thenReturn(new HostCost(
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20), BigDecimal.ZERO, List.of()));
        when(repository.findByUserId(user)).thenReturn(List.of(CostBudget.builder()
                .userId(user).hostId(host).amountEur(new BigDecimal("50.00")).build()));

        CostSummary summary = service.summary(user, "week");

        assertThat(summary.clients()).hasSize(1);
        assertThat(summary.clients().get(0).spentEur()).isEqualByComparingTo("0");
        assertThat(summary.clients().get(0).percent()).isZero();
        assertThat(summary.clients().get(0).ownBudget()).isTrue();
    }

    @Test
    void theOutsideBucketNeverGetsABudget() {
        when(costs.costs(eq(user), any())).thenReturn(new HostCost(
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20), new BigDecimal("12.00"),
                List.of(new HostCost.Client(null, null, new BigDecimal("12.00"), 10L))));

        CostSummary summary = service.summary(user, "week");

        assertThat(summary.clients().get(0).budgetEur()).isNull();
        assertThat(summary.clients().get(0).percent()).isNull();
    }

    @Test
    void theMonthIsTheCurrentCalendarMonth() {
        givenOneClient("10.00");
        when(budgets.budgetOf(user, host)).thenReturn(Optional.empty());

        CostSummary summary = service.summary(user, "month");

        assertThat(summary.period()).isEqualTo("month");
    }

    @Test
    void refusesAnUnknownPeriod() {
        assertThatThrownBy(() -> service.summary(user, "trimestre"))
                .isInstanceOf(InvalidUsageWindowException.class)
                .hasMessageContaining("Période inconnue");
    }

    // ---------------------------------------- tous les clients budgétables (SF-133-13)

    @Test
    void aClientThatNeverSpentIsStillListed() {
        // LE CRITÈRE DE LA SUBFEATURE. C'est au moment où un client n'a PAS ENCORE dépensé qu'on
        // veut lui poser un plafond ; tant que la liste venait de la dépense, on ne pouvait le
        // faire qu'après coup.
        givenNoSpend();
        givenHosts(hostNamed(host, "poste-kg"));

        CostSummary summary = service.summary(user, "week");

        assertThat(summary.clients()).hasSize(1);
        assertThat(summary.clients().get(0).hostId()).isEqualTo(host);
        assertThat(summary.clients().get(0).hostName()).isEqualTo("poste-kg");
        assertThat(summary.clients().get(0).spentEur()).isEqualByComparingTo("0");
    }

    @Test
    void aClientThatSpentIsNotListedTwice() {
        givenOneClient("40.00");
        givenHosts(hostNamed(host, "poste-cagip"));
        when(budgets.budgetOf(user, host)).thenReturn(Optional.of(new BigDecimal("100.00")));

        CostSummary summary = service.summary(user, "week");

        assertThat(summary.clients()).hasSize(1);
        // Et le budget n'est compté qu'une fois dans le total.
        assertThat(summary.budgetEur()).isEqualByComparingTo("100.00");
    }

    @Test
    void aBudgetedClientWithoutSpendIsNotListedTwiceEither() {
        // Le chemin de SF-133-07 (budget sans dépense) et celui de SF-133-13 (poste sans dépense)
        // désignent ici le même client : il ne doit apparaître qu'une fois.
        givenNoSpend();
        givenHosts(hostNamed(host, "poste-kg"));
        when(budgets.budgetOf(user, host)).thenReturn(Optional.of(new BigDecimal("50.00")));
        when(repository.findByUserId(user)).thenReturn(List.of(CostBudget.builder()
                .userId(user).hostId(host).amountEur(new BigDecimal("50.00")).build()));

        CostSummary summary = service.summary(user, "week");

        assertThat(summary.clients()).hasSize(1);
        assertThat(summary.budgetEur()).isEqualByComparingTo("50.00");
    }

    @Test
    void theDefaultBudgetAppliesToEachClient() {
        // La règle que SF-133-14 va écrire à l'écran : le défaut n'est PAS une enveloppe commune.
        // Deux clients à 50 € de défaut plafonnent la semaine à 100 €.
        UUID second = UUID.randomUUID();
        givenNoSpend();
        givenHosts(hostNamed(host, "poste-cagip"), hostNamed(second, "poste-kg"));
        when(budgets.budgetOf(eq(user), any())).thenReturn(Optional.of(new BigDecimal("50.00")));

        CostSummary summary = service.summary(user, "week");

        assertThat(summary.clients()).hasSize(2);
        assertThat(summary.budgetEur()).isEqualByComparingTo("100.00");
    }

    @Test
    void aClosedMissionIsNotBudgetable() {
        // On ne pose pas de plafond sur une mission terminée — et la liste resterait encombrée
        // d'anciens clients pour toujours.
        givenNoSpend();
        when(hosts.findByUserIdAndMissionStatusNotOrderByCreatedAtAsc(user, HostMissionStatus.CLOSED))
                .thenReturn(List.of());

        assertThat(service.summary(user, "week").clients()).isEmpty();
    }

    private void givenNoSpend() {
        when(costs.costs(eq(user), any())).thenReturn(new HostCost(
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20), BigDecimal.ZERO, List.of()));
    }

    private void givenHosts(RunnerHost... list) {
        when(hosts.findByUserIdAndMissionStatusNotOrderByCreatedAtAsc(user, HostMissionStatus.CLOSED))
                .thenReturn(List.of(list));
    }

    private RunnerHost hostNamed(UUID id, String name) {
        return RunnerHost.builder().id(id).userId(user).name(name).build();
    }

    private void givenOneClient(String spent) {
        when(costs.costs(eq(user), any())).thenReturn(new HostCost(
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20), new BigDecimal(spent),
                List.of(new HostCost.Client(host, "poste-cagip", new BigDecimal(spent), 100L))));
    }
}
