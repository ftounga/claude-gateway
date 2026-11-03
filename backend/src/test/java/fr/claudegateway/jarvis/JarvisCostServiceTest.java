package fr.claudegateway.jarvis;

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
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Le compteur visible de Jarvis (F-239 / SF-239-12) : aujourd'hui et le mois, en euros, par poste. */
class JarvisCostServiceTest {

    @Test
    void todayAndTheMonthInEurosPerItem() {
        UUID alice = UUID.randomUUID();
        JarvisUsageDayRepository repository = mock(JarvisUsageDayRepository.class);
        JarvisUsageDay yesterday = JarvisUsageDay.builder().userId(alice).day(LocalDate.of(2026, 11, 1))
                .fastBrainCostUsd(new BigDecimal("1")).opusCostUsd(new BigDecimal("2")).opusTurns(2)
                .speechCostUsd(new BigDecimal("0.5")).sttStreamCostUsd(new BigDecimal("0.1")).build();
        JarvisUsageDay today = JarvisUsageDay.builder().userId(alice).day(LocalDate.of(2026, 11, 2))
                .fastBrainCostUsd(new BigDecimal("0.10")).opusCostUsd(new BigDecimal("1.06")).opusTurns(1)
                .speechCostUsd(BigDecimal.ZERO).sttStreamCostUsd(new BigDecimal("0.004"))
                // F-244 / SF-244-01 : retranscription et dictée, en colonnes séparées, comptent dans l'écoute.
                .sttUtteranceCostUsd(new BigDecimal("0.004")).dictationCostUsd(new BigDecimal("0.002")).build();
        when(repository.findByUserIdAndDayGreaterThanEqualOrderByDayAsc(eq(alice), any()))
                .thenReturn(List.of(yesterday, today));
        JarvisCostService service = new JarvisCostService(repository,
                Clock.fixed(Instant.parse("2026-11-02T09:00:00Z"), ZoneOffset.UTC));

        JarvisCostService.Summary costs = service.costs(alice);

        assertThat(costs.today().opus()).isEqualByComparingTo("0.98"); // 1,06 $ × 0,92
        assertThat(costs.today().total()).isEqualByComparingTo("1.08"); // 1,17 $ × 0,92
        assertThat(costs.today().opusTurns()).isEqualTo(1);
        assertThat(costs.month().total()).isEqualByComparingTo("4.39"); // 4,77 $ × 0,92 = 4,3884
        assertThat(costs.month().opusTurns()).isEqualTo(3);
    }
}
