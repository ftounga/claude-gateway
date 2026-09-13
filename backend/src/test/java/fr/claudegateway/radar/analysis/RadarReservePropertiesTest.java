package fr.claudegateway.radar.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

/** F-101 / SF-101-05 — la réserve configurée et le taux de corrections. */
class RadarReservePropertiesTest {

    @Test
    void defaultsAndBounds() {
        RadarReserveProperties defaults = RadarReserveProperties.defaults();
        assertThat(defaults.monthlyTokens()).isEqualTo(3_000_000L);
        assertThat(defaults.perSyncTokens()).isZero();

        RadarReserveProperties aberrant = new RadarReserveProperties(-5L, 12L);
        assertThat(aberrant.monthlyTokens()).isEqualTo(3_000_000L);
        assertThat(aberrant.perSyncTokens()).isZero();

        RadarReserveProperties set = new RadarReserveProperties(500_000L, 100_000L);
        assertThat(set.monthlyTokens()).isEqualTo(500_000L);
        assertThat(set.perSyncTokens()).isEqualTo(100_000L);
    }

    @Test
    void theMonthIsCivilAndUtc() {
        OffsetDateTime lateEvening = OffsetDateTime.parse("2026-09-30T23:30:00-02:00"); // déjà octobre en UTC
        assertThat(RadarReserveProperties.monthStart(lateEvening)).isEqualTo(OffsetDateTime.parse("2026-10-01T00:00:00Z"));
        assertThat(RadarReserveProperties.nextMonthStart(OffsetDateTime.parse("2026-12-15T10:00:00Z")))
                .isEqualTo(OffsetDateTime.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    void correctionRate() {
        assertThat(RadarAnalysisReport.rate(1, 3)).isEqualTo(0.333);
        assertThat(RadarAnalysisReport.rate(0, 5)).isEqualTo(0.0);
        assertThat(RadarAnalysisReport.rate(2, 0)).isNull();
    }
}
