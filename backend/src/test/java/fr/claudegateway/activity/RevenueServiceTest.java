package fr.claudegateway.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.activity.RevenueService.PosteRevenue;
import fr.claudegateway.activity.RevenueService.RevenueSummary;

/** Le calcul du cumul de revenu (F-124 / SF-124-02) : mois complet, déclaré, demi-journées, courant. */
@ExtendWith(MockitoExtension.class)
class RevenueServiceTest {

    @Mock private ActivityConfigService configService;
    @Mock private CraEntryRepository craRepository;

    private RevenueService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID hostA = UUID.randomUUID();
    private final YearMonth start = YearMonth.of(2025, 9);
    private static final long RATE = 55_000L; // 550 €/j

    @BeforeEach
    void setUp() {
        service = new RevenueService(configService, craRepository);
        when(configService.startMonth(userId)).thenReturn(start);
    }

    private static long supposed(YearMonth month) {
        return WorkdayCalendar.businessDaysInMonth(month) * RATE;
    }

    private PosteRevenue only(RevenueSummary summary) {
        assertThat(summary.postes()).hasSize(1);
        return summary.postes().get(0);
    }

    @Test
    void pastMonthsWithoutCra_areSupposed_currentMonthIsNot() {
        when(configService.ratesByHost(userId)).thenReturn(Map.of(hostA, RATE));
        when(craRepository.findByUserId(userId)).thenReturn(List.of());

        // Mois courant = novembre 2025 : septembre et octobre sont supposés, novembre non.
        RevenueSummary summary = service.compute(userId, YearMonth.of(2025, 11));
        long expected = supposed(YearMonth.of(2025, 9)) + supposed(YearMonth.of(2025, 10));

        PosteRevenue poste = only(summary);
        assertThat(poste.supposedCents()).isEqualTo(expected);
        assertThat(poste.declaredCents()).isZero();
        assertThat(poste.cumulCents()).isEqualTo(expected);
        assertThat(summary.totalSupposedCents()).isEqualTo(expected);
        assertThat(summary.totalCents()).isEqualTo(expected);
    }

    @Test
    void declaredMonthCountsDeclaredDays_notAFullMonth() {
        when(configService.ratesByHost(userId)).thenReturn(Map.of(hostA, RATE));
        // Septembre déclaré à 20 jours ; octobre non déclaré (supposé) ; courant = novembre.
        when(craRepository.findByUserId(userId)).thenReturn(List.of(
                cra(hostA, "2025-09", new BigDecimal("20"))));

        RevenueSummary summary = service.compute(userId, YearMonth.of(2025, 11));
        PosteRevenue poste = only(summary);

        assertThat(poste.declaredCents()).isEqualTo(20 * RATE);
        assertThat(poste.supposedCents()).isEqualTo(supposed(YearMonth.of(2025, 10)));
        assertThat(poste.cumulCents()).isEqualTo(20 * RATE + supposed(YearMonth.of(2025, 10)));
    }

    @Test
    void currentMonthDeclared_isCounted() {
        when(configService.ratesByHost(userId)).thenReturn(Map.of(hostA, RATE));
        // Mois courant = septembre (= mois de départ), déclaré à 10 jours.
        when(craRepository.findByUserId(userId)).thenReturn(List.of(
                cra(hostA, "2025-09", new BigDecimal("10"))));

        RevenueSummary summary = service.compute(userId, YearMonth.of(2025, 9));
        PosteRevenue poste = only(summary);

        assertThat(poste.declaredCents()).isEqualTo(10 * RATE);
        assertThat(poste.supposedCents()).isZero();
    }

    @Test
    void halfDaysAreAllowed_andRoundedToTheCent() {
        when(configService.ratesByHost(userId)).thenReturn(Map.of(hostA, RATE));
        when(craRepository.findByUserId(userId)).thenReturn(List.of(
                cra(hostA, "2025-09", new BigDecimal("0.5"))));

        RevenueSummary summary = service.compute(userId, YearMonth.of(2025, 9));
        assertThat(only(summary).declaredCents()).isEqualTo(27_500L); // 0,5 × 550 € = 275 €
    }

    @Test
    void currentMonthUndeclared_addsNothing() {
        when(configService.ratesByHost(userId)).thenReturn(Map.of(hostA, RATE));
        when(craRepository.findByUserId(userId)).thenReturn(List.of());

        // Mois de départ = mois courant, rien déclaré → tout à zéro (mois inachevé non supposé).
        RevenueSummary summary = service.compute(userId, YearMonth.of(2025, 9));
        assertThat(only(summary).cumulCents()).isZero();
        assertThat(summary.totalCents()).isZero();
    }

    @Test
    void sumsAcrossHosts() {
        UUID hostB = UUID.randomUUID();
        when(configService.ratesByHost(userId)).thenReturn(Map.of(hostA, RATE, hostB, 70_000L));
        when(craRepository.findByUserId(userId)).thenReturn(List.of());

        RevenueSummary summary = service.compute(userId, YearMonth.of(2025, 10));
        long sep = WorkdayCalendar.businessDaysInMonth(YearMonth.of(2025, 9));
        long expected = sep * RATE + sep * 70_000L;

        assertThat(summary.postes()).hasSize(2);
        assertThat(summary.totalCents()).isEqualTo(expected);
    }

    @Test
    void hostWithoutRateIsIgnored() {
        // ratesByHost ne rend que les postes AYANT un TJM : un poste sans TJM n'y est pas.
        when(configService.ratesByHost(userId)).thenReturn(Map.of());
        when(craRepository.findByUserId(userId)).thenReturn(List.of(
                cra(UUID.randomUUID(), "2025-09", new BigDecimal("20"))));

        RevenueSummary summary = service.compute(userId, YearMonth.of(2025, 11));
        assertThat(summary.postes()).isEmpty();
        assertThat(summary.totalCents()).isZero();
    }

    private CraEntry cra(UUID hostId, String month, BigDecimal days) {
        return CraEntry.builder().userId(userId).hostId(hostId).yearMonth(month).days(days).build();
    }
}
