package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests unitaires du rapport d'usage &amp; coût (SF-16-01) : agrégation, tri/limite, coût estimé au
 * tarif configuré, marquage de la période courante et totaux. Horloge figée pour rendre la période
 * courante déterministe.
 */
@ExtendWith(MockitoExtension.class)
class UsageReportServiceTest {

    @Mock
    private UsageCounterRepository usageCounterRepository;

    private UsageReportService service;

    private final UUID alice = UUID.randomUUID();
    // 2026-07-15 → période courante attendue : 2026-07-01.
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-15T10:00:00Z"), ZoneOffset.UTC);
    private final LocalDate july = LocalDate.of(2026, 7, 1);
    private final LocalDate june = LocalDate.of(2026, 6, 1);
    private final LocalDate may = LocalDate.of(2026, 5, 1);

    // Tarifs de test : 3.00 €/M entrée, 15.00 €/M sortie (défauts).
    private final UsageReportProperties properties =
            new UsageReportProperties("EUR", 12, new BigDecimal("3.00"), new BigDecimal("15.00"));

    @BeforeEach
    void setUp() {
        service = new UsageReportService(usageCounterRepository, properties, clock);
    }

    private UsageCounter counter(LocalDate period, long input, long output) {
        return UsageCounter.builder()
                .userId(alice).periodStart(period).inputTokens(input).outputTokens(output).build();
    }

    @Test
    void emptyReportWhenNoCounters() {
        when(usageCounterRepository.findByUserIdOrderByPeriodStartDesc(alice)).thenReturn(List.of());

        UsageReport report = service.buildReport(alice);

        assertThat(report.currency()).isEqualTo("EUR");
        assertThat(report.periods()).isEmpty();
        assertThat(report.totalInputTokens()).isZero();
        assertThat(report.totalOutputTokens()).isZero();
        assertThat(report.totalTokens()).isZero();
        assertThat(report.totalEstimatedCost()).isEqualByComparingTo("0");
    }

    @Test
    void singlePeriodComputesTokensAndEstimatedCostAndMarksCurrent() {
        when(usageCounterRepository.findByUserIdOrderByPeriodStartDesc(alice))
                .thenReturn(List.of(counter(july, 12_000, 8_000)));

        UsageReport report = service.buildReport(alice);

        assertThat(report.periods()).hasSize(1);
        UsagePeriod p = report.periods().get(0);
        assertThat(p.periodStart()).isEqualTo(july);
        assertThat(p.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(p.inputTokens()).isEqualTo(12_000);
        assertThat(p.outputTokens()).isEqualTo(8_000);
        assertThat(p.totalTokens()).isEqualTo(20_000);
        // 12000*3/1e6 + 8000*15/1e6 = 0.036 + 0.12 = 0.156
        assertThat(p.estimatedCost()).isEqualByComparingTo("0.1560");
        assertThat(p.current()).isTrue();
        assertThat(report.totalEstimatedCost()).isEqualByComparingTo("0.1560");
    }

    @Test
    void periodsKeptDescendingAndTotalsSummed() {
        when(usageCounterRepository.findByUserIdOrderByPeriodStartDesc(alice))
                .thenReturn(List.of(
                        counter(july, 1_000, 2_000),
                        counter(june, 3_000, 0),
                        counter(may, 0, 5_000)));

        UsageReport report = service.buildReport(alice);

        assertThat(report.periods()).extracting(UsagePeriod::periodStart)
                .containsExactly(july, june, may);
        assertThat(report.periods().get(0).current()).isTrue();
        assertThat(report.periods().get(1).current()).isFalse();
        assertThat(report.periods().get(2).current()).isFalse();
        assertThat(report.totalInputTokens()).isEqualTo(4_000);
        assertThat(report.totalOutputTokens()).isEqualTo(7_000);
        assertThat(report.totalTokens()).isEqualTo(11_000);
        // july (1000·3 + 2000·15)/1e6 = 0.033 ; june 3000·3/1e6 = 0.009 ; may 5000·15/1e6 = 0.075
        // → total 0.117
        assertThat(report.totalEstimatedCost()).isEqualByComparingTo("0.1170");
    }

    @Test
    void windowLimitedToMaxMonths() {
        UsageReportProperties limited =
                new UsageReportProperties("EUR", 2, new BigDecimal("3.00"), new BigDecimal("15.00"));
        UsageReportService limitedService =
                new UsageReportService(usageCounterRepository, limited, clock);
        when(usageCounterRepository.findByUserIdOrderByPeriodStartDesc(alice))
                .thenReturn(List.of(
                        counter(july, 1_000, 0),
                        counter(june, 1_000, 0),
                        counter(may, 1_000, 0)));

        UsageReport report = limitedService.buildReport(alice);

        assertThat(report.periods()).extracting(UsagePeriod::periodStart).containsExactly(july, june);
        assertThat(report.totalInputTokens()).isEqualTo(2_000);
    }
}
