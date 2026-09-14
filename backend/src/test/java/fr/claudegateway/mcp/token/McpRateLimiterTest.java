package fr.claudegateway.mcp.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Limite par jeton (F-112 / SF-112-03) : 60 appels passent, le 61e est refusé ; deux jetons sont
 * indépendants.
 */
class McpRateLimiterTest {

    @Test
    void allowsSixtyThenRefuses() {
        McpRateLimiter limiter = new McpRateLimiter();
        UUID token = UUID.randomUUID();
        for (int i = 0; i < McpRateLimiter.MAX_PER_MINUTE; i++) {
            assertThat(limiter.tryAcquire(token)).as("appel %d", i).isTrue();
        }
        assertThat(limiter.tryAcquire(token)).isFalse();
    }

    @Test
    void tokensAreIndependent() {
        McpRateLimiter limiter = new McpRateLimiter();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        for (int i = 0; i < McpRateLimiter.MAX_PER_MINUTE; i++) {
            limiter.tryAcquire(a);
        }
        assertThat(limiter.tryAcquire(a)).isFalse();
        assertThat(limiter.tryAcquire(b)).isTrue();
    }

    /**
     * F-117 / SF-117-04 : au-delà du seuil de jetons suivis, les fenêtres périmées sont purgées —
     * la carte ne grossit plus sans borne sur un pod de longue vie.
     */
    @Test
    void purgesStaleWindowsBeyondTheThreshold() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-14T10:00:00Z"));
        McpRateLimiter limiter = new McpRateLimiter(clock);

        // Plus de 1000 jetons vus une fois, tous à t0.
        for (int i = 0; i < 1_100; i++) {
            limiter.tryAcquire(UUID.randomUUID());
        }
        assertThat(limiter.trackedTokens()).isGreaterThan(1_000);

        // Deux minutes plus tard, leurs fenêtres sont périmées (> 1 min). Un nouvel appel déclenche
        // la purge : il ne reste que le jeton courant.
        clock.advance(Duration.ofMinutes(2));
        assertThat(limiter.tryAcquire(UUID.randomUUID())).isTrue();
        assertThat(limiter.trackedTokens()).isEqualTo(1);
    }

    /** Horloge mutable minimale pour piloter la fenêtre glissante dans le test. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration by) {
            this.now = this.now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
