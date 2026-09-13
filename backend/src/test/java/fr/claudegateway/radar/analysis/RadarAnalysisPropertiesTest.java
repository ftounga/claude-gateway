package fr.claudegateway.radar.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** F-101 / SF-101-01 — un réglage aberrant retombe sur le défaut ; la rétention ne dépasse jamais 7 jours. */
class RadarAnalysisPropertiesTest {

    @Test
    void aberrantValuesFallBackToDefaults() {
        RadarAnalysisProperties p = new RadarAnalysisProperties(null, 0, Duration.ofDays(30), Duration.ZERO, -1,
                10_000, Duration.ofSeconds(1));

        assertThat(p.enabled()).isTrue();
        assertThat(p.maxAttempts()).isEqualTo(RadarAnalysisProperties.DEFAULT_MAX_ATTEMPTS);
        assertThat(p.rawRetention()).isEqualTo(Duration.ofDays(7));
        assertThat(p.leaseDuration()).isEqualTo(RadarAnalysisProperties.DEFAULT_LEASE);
        assertThat(p.hostsPerRun()).isEqualTo(RadarAnalysisProperties.DEFAULT_HOSTS_PER_RUN);
        assertThat(p.batchesPerHost()).isEqualTo(RadarAnalysisProperties.DEFAULT_BATCHES_PER_HOST);
        assertThat(p.deferDelay()).isEqualTo(RadarAnalysisProperties.DEFAULT_DEFER_DELAY);
    }

    @Test
    void shorterRetentionIsKept() {
        assertThat(new RadarAnalysisProperties(false, 5, Duration.ofDays(2), null, null, null, null).rawRetention())
                .isEqualTo(Duration.ofDays(2));
    }

    @Test
    void backoffGrows() {
        RadarAnalysisProperties p = RadarAnalysisProperties.defaults();
        assertThat(p.backoff(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(p.backoff(2)).isEqualTo(Duration.ofMinutes(5));
        assertThat(p.backoff(3)).isEqualTo(Duration.ofMinutes(30));
    }
}
