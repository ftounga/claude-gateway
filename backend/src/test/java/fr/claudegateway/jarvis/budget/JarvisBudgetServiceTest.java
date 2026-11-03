package fr.claudegateway.jarvis.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.jarvis.JarvisCostService;
import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushNotificationService;

/** Le plafond de Jarvis (F-244 / SF-244-03) : seuils, porte d'une délégation, BYOK, alerte unique, réglage. */
class JarvisBudgetServiceTest {

    private final UUID alice = UUID.randomUUID();
    private final Map<UUID, JarvisBudget> rows = new HashMap<>();
    private JarvisBudgetRepository repository;
    private JarvisCostService costs;
    private ByokKeyService byok;
    private PushNotificationService push;
    private JarvisBudgetService service;

    @BeforeEach
    void setUp() {
        repository = mock(JarvisBudgetRepository.class);
        when(repository.findById(any())).thenAnswer(i -> Optional.ofNullable(rows.get(i.getArgument(0))));
        when(repository.save(any())).thenAnswer(i -> {
            JarvisBudget row = i.getArgument(0);
            rows.put(row.getUserId(), row);
            return row;
        });
        costs = mock(JarvisCostService.class);
        byok = mock(ByokKeyService.class);
        when(byok.resolveActiveApiKey(any())).thenReturn(Optional.empty());
        push = mock(PushNotificationService.class);
        service = new JarvisBudgetService(repository, costs,
                Clock.fixed(Instant.parse("2026-11-03T09:00:00Z"), ZoneOffset.UTC), new BigDecimal("100"));
        service.setByok(byok);
        service.setPush(push);
        spent("0");
    }

    private void spent(String eur) {
        spent(eur, "0", 0);
    }

    private void spent(String eur, String opusEur, int opusTurns) {
        JarvisCostService.Costs month = new JarvisCostService.Costs(BigDecimal.ZERO, new BigDecimal(opusEur),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(eur), opusTurns);
        when(costs.costs(alice)).thenReturn(new JarvisCostService.Summary(month, month));
    }

    @Test
    void theThresholdsAre80And100And200Percent() {
        BigDecimal cap = new BigDecimal("100");
        assertThat(JarvisBudgetService.stateOf(new BigDecimal("79.99"), cap)).isEqualTo(JarvisBudgetService.State.NONE);
        assertThat(JarvisBudgetService.stateOf(new BigDecimal("80"), cap)).isEqualTo(JarvisBudgetService.State.ALERT);
        assertThat(JarvisBudgetService.stateOf(new BigDecimal("100"), cap)).isEqualTo(JarvisBudgetService.State.SOFT);
        assertThat(JarvisBudgetService.stateOf(new BigDecimal("199.99"), cap)).isEqualTo(JarvisBudgetService.State.SOFT);
        assertThat(JarvisBudgetService.stateOf(new BigDecimal("200"), cap)).isEqualTo(JarvisBudgetService.State.HARD);
        assertThat(JarvisBudgetService.stateOf(BigDecimal.ZERO, BigDecimal.ZERO)).isEqualTo(JarvisBudgetService.State.HARD);
    }

    @Test
    void theDefaultCapAppliesWithoutARow() {
        spent("40");
        JarvisBudgetService.Status status = service.status(alice);
        assertThat(status.monthlyCapEur()).isEqualByComparingTo("100");
        assertThat(status.percent()).isEqualTo(40);
        assertThat(status.state()).isEqualTo(JarvisBudgetService.State.NONE);
        assertThat(status.applies()).isTrue();
    }

    @Test
    void the80PercentAlertIsSentOncePerMonth() {
        spent("85");
        service.status(alice);
        service.status(alice);
        service.status(alice);
        verify(push, times(1)).notify(eq(alice), eq(null), eq(PushEvent.JARVIS_BUDGET_ALERT), eq("/jarvis"));
        assertThat(rows.get(alice).getAlert80SentMonth()).isEqualTo("2026-11");
    }

    @Test
    void noAlertBelow80Percent() {
        spent("79");
        service.status(alice);
        verify(push, never()).notify(any(), any(), any(), any());
    }

    @Test
    void belowTheCapADelegationGoesThrough() {
        spent("99");
        assertThatCode(() -> service.gateDelegation(alice, true)).doesNotThrowAnyException();
    }

    @Test
    void overTheCapADelegationAsksAndGoesOnlyAfterAYesOnce() {
        spent("120", "6", 3);
        assertThatThrownBy(() -> service.gateDelegation(alice, true))
                .isInstanceOf(JarvisBudgetException.class)
                .satisfies(e -> assertThat(((JarvisBudgetException) e).code()).isEqualTo(JarvisBudgetException.CONFIRM))
                .hasMessage("Budget Jarvis du mois atteint ; cette recherche coûte environ 2 €. Je la lance ?");

        service.confirm(alice);
        assertThatCode(() -> service.gateDelegation(alice, true)).doesNotThrowAnyException();
        // Le « oui » vaut pour UNE délégation.
        assertThatThrownBy(() -> service.gateDelegation(alice, true)).isInstanceOf(JarvisBudgetException.class);
    }

    @Test
    void atTwiceTheCapNothingStartsEvenWithAYes() {
        spent("200");
        service.confirm(alice);
        assertThatThrownBy(() -> service.gateDelegation(alice, true))
                .satisfies(e -> assertThat(((JarvisBudgetException) e).code()).isEqualTo(JarvisBudgetException.HARD))
                .hasMessage(JarvisBudgetService.HARD_REPLY);
        assertThat(service.hardStop(alice)).isTrue();
    }

    @Test
    void inByokTheCapOnlyBlocksWithTheOption() {
        spent("250");
        when(byok.resolveActiveApiKey(alice)).thenReturn(Optional.of("sk-ant-client"));
        assertThatCode(() -> service.gateDelegation(alice, false)).doesNotThrowAnyException();
        assertThat(service.hardStop(alice)).isFalse();

        service.update(alice, null, true);
        assertThatThrownBy(() -> service.gateDelegation(alice, false)).isInstanceOf(JarvisBudgetException.class);
        assertThat(service.hardStop(alice)).isTrue();
    }

    @Test
    void raisingTheCapChangesTheStateImmediately() {
        spent("120");
        assertThat(service.status(alice).state()).isEqualTo(JarvisBudgetService.State.SOFT);
        JarvisBudgetService.Status raised = service.update(alice, new BigDecimal("300"), null);
        assertThat(raised.state()).isEqualTo(JarvisBudgetService.State.NONE);
        assertThat(raised.monthlyCapEur()).isEqualByComparingTo("300");
        assertThatCode(() -> service.gateDelegation(alice, true)).doesNotThrowAnyException();
    }

    @Test
    void anOutOfBoundsCapIsRefused() {
        assertThatThrownBy(() -> service.update(alice, new BigDecimal("-1"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(alice, new BigDecimal("10001"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnreadableBudgetNeverBlocks() {
        when(costs.costs(alice)).thenThrow(new IllegalStateException("base"));
        assertThatCode(() -> service.gateDelegation(alice, true)).doesNotThrowAnyException();
        assertThat(service.hardStop(alice)).isFalse();
    }
}
