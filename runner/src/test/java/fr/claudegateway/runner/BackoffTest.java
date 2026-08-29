package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class BackoffTest {

    @Test
    void doubles_until_capped() {
        Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(30));
        assertEquals(1, backoff.nextDelay().toSeconds());
        assertEquals(2, backoff.nextDelay().toSeconds());
        assertEquals(4, backoff.nextDelay().toSeconds());
        assertEquals(8, backoff.nextDelay().toSeconds());
        assertEquals(16, backoff.nextDelay().toSeconds());
        assertEquals(30, backoff.nextDelay().toSeconds()); // 32 plafonné à 30
        assertEquals(30, backoff.nextDelay().toSeconds());
    }

    @Test
    void reset_returns_to_initial() {
        Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(30));
        backoff.nextDelay();
        backoff.nextDelay();
        backoff.reset();
        assertEquals(1, backoff.nextDelay().toSeconds());
    }

    @Test
    void rejects_invalid_bounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new Backoff(Duration.ZERO, Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class,
                () -> new Backoff(Duration.ofSeconds(30), Duration.ofSeconds(1)));
    }
}
