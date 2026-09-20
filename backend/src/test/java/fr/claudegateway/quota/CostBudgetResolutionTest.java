package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * La résolution d'un budget (F-133 / SF-133-04) : le sien, sinon le défaut, sinon aucun.
 */
class CostBudgetResolutionTest {

    private final CostBudgetRepository repository = mock(CostBudgetRepository.class);
    private final RunnerHostRepository hosts = mock(RunnerHostRepository.class);
    private final CostBudgetService service = new CostBudgetService(repository, hosts,
            mock(AdminService.class), Clock.fixed(Instant.parse("2026-09-20T10:00:00Z"),
            ZoneOffset.UTC));

    private final UUID user = UUID.randomUUID();
    private final UUID host = UUID.randomUUID();

    @Test
    void theOwnBudgetWins() {
        when(repository.findByUserIdAndHostId(user, host))
                .thenReturn(Optional.of(budget(host, "60.00")));

        assertThat(service.budgetOf(user, host)).contains(new BigDecimal("60.00"));
    }

    @Test
    void withoutItsOwnTheDefaultApplies() {
        when(repository.findByUserIdAndHostId(user, host)).thenReturn(Optional.empty());
        when(repository.findByUserIdAndHostIdIsNull(user))
                .thenReturn(Optional.of(budget(null, "120.00")));

        assertThat(service.budgetOf(user, host)).contains(new BigDecimal("120.00"));
    }

    @Test
    void withoutAnyThereIsNoBudget() {
        // Et surtout : pas de zéro. Un budget absent n'est pas un budget nul — l'écran dira la
        // dépense SANS part consommée, plutôt qu'une part inventée.
        when(repository.findByUserIdAndHostId(user, host)).thenReturn(Optional.empty());
        when(repository.findByUserIdAndHostIdIsNull(user)).thenReturn(Optional.empty());

        assertThat(service.budgetOf(user, host)).isEmpty();
    }

    @Test
    void refusesANegativeAmountAndAnAbsurdOne() {
        when(hosts.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setDefault(user, new BigDecimal("-0.01")))
                .isInstanceOf(InvalidCostBudgetException.class)
                .hasMessageContaining("négatif");
        assertThatThrownBy(() -> service.setDefault(user, new BigDecimal("1000000.01")))
                .isInstanceOf(InvalidCostBudgetException.class)
                .hasMessageContaining("plafond");
    }

    @Test
    void roundsToTheCent() {
        when(repository.findByUserIdAndHostIdIsNull(user)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        assertThat(service.setDefault(user, new BigDecimal("12.3456")).getAmountEur())
                .isEqualByComparingTo("12.35");
    }

    private CostBudget budget(UUID hostId, String amount) {
        return CostBudget.builder().userId(user).hostId(hostId)
                .amountEur(new BigDecimal(amount)).build();
    }
}
