package fr.claudegateway.atelier.veille;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** F-230 / SF-230-04 (D8) — la mesure d'adoption : veilles, passages, changements, alertes ouvertes, coût en €. */
class JarvisWatchMeasureServiceTest {

    @Test
    void countsWhatTheWatchesDidOnTheWindow() {
        UUID userId = UUID.randomUUID();
        JarvisWatchRepository watches = mock(JarvisWatchRepository.class);
        JarvisWatchRunRepository runs = mock(JarvisWatchRunRepository.class);
        JarvisWatchAlertRepository alerts = mock(JarvisWatchAlertRepository.class);
        Clock clock = Clock.fixed(OffsetDateTime.parse("2026-11-09T08:00:00Z").toInstant(), ZoneOffset.UTC);
        JarvisAlertService alertService = new JarvisAlertService(alerts, watches, new JarvisAlertLinks(false), clock);
        when(watches.findByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of(
                JarvisWatch.builder().active(true).proposed(false).build(),
                JarvisWatch.builder().active(false).proposed(true).build(),
                JarvisWatch.builder().active(true).proposed(false).broken(true).build()));
        when(runs.findByUserIdAndStartedAtGreaterThanEqual(eq(userId), any())).thenReturn(List.of(
                run(JarvisWatchRun.BASELINE), run(JarvisWatchRun.NO_CHANGE), run(JarvisWatchRun.NO_CHANGE),
                run(JarvisWatchRun.CHANGED), run(JarvisWatchRun.FAILED), run(JarvisWatchRun.MISSED_OFFLINE)));
        when(alerts.findByUserIdAndCreatedAtGreaterThanEqual(eq(userId), any())).thenReturn(List.of(
                JarvisWatchAlert.builder().costUsd(new BigDecimal("0.10")).openedAt(OffsetDateTime.now(clock)).build(),
                JarvisWatchAlert.builder().costUsd(new BigDecimal("0.05")).build()));

        JarvisWatchMeasureService.Measure measure = new JarvisWatchMeasureService(watches, runs, alerts, alertService,
                clock).measure(userId, 7);

        assertThat(measure.watches()).isEqualTo(3);
        assertThat(measure.active()).isEqualTo(2);
        assertThat(measure.proposed()).isEqualTo(1);
        assertThat(measure.broken()).isEqualTo(1);
        assertThat(measure.passages()).isEqualTo(5);
        assertThat(measure.changes()).isEqualTo(1);
        assertThat(measure.failures()).isEqualTo(1);
        assertThat(measure.missed()).isEqualTo(1);
        assertThat(measure.alerts()).isEqualTo(2);
        assertThat(measure.alertsOpened()).isEqualTo(1);
        assertThat(measure.aiCostEur()).isEqualByComparingTo("0.14");
    }

    private static JarvisWatchRun run(String status) {
        return JarvisWatchRun.builder().status(status).build();
    }
}
