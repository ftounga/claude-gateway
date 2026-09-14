package fr.claudegateway.mcp.token;

import static org.assertj.core.api.Assertions.assertThat;

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
}
