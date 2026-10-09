package fr.claudegateway.pages.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Le cache court des PDF imprimés (F-184 / SF-184-04) : expiration, bornes en nombre et en octets. */
class PagePdfCacheTest {

    private static PagePdfService.PagePdf pdf(int bytes) {
        return new PagePdfService.PagePdf(new byte[bytes], "p.pdf", "", "P", 1);
    }

    /** Une horloge qu'on avance à la main. */
    private static final class Hand extends Clock {
        private Instant now = Instant.parse("2026-10-10T10:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("CA5 — servi dans le délai, expiré au-delà")
    void expires() {
        Hand clock = new Hand();
        PagePdfCache cache = new PagePdfCache(Duration.ofMinutes(10), 20, 1_000, clock);
        cache.put("a", pdf(10));

        clock.now = clock.now.plus(Duration.ofMinutes(9));
        assertThat(cache.get("a")).isPresent();
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertThat(cache.get("a")).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("CA5 — borné en nombre (le plus ancien sort) et en octets ; un PDF trop gros n'entre pas")
    void bounded() {
        PagePdfCache cache = new PagePdfCache(Duration.ofMinutes(10), 2, 100, new Hand());
        cache.put("a", pdf(10));
        cache.put("b", pdf(10));
        cache.put("c", pdf(10));
        assertThat(cache.get("a")).isEmpty();
        assertThat(cache.get("c")).isPresent();

        cache.put("d", pdf(95));
        assertThat(cache.get("c")).isEmpty();
        assertThat(cache.get("d")).isPresent();

        cache.put("e", pdf(101));
        assertThat(cache.get("e")).isEmpty();
    }
}
