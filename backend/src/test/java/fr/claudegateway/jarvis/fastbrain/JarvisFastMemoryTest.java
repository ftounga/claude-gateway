package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** F-235 / SF-235-02 — la mémoire de session : 12 échanges au plus, 30 minutes, comptes séparés. */
class JarvisFastMemoryTest {

    private static final Instant NOW = Instant.parse("2026-11-01T13:32:00Z");

    @Test
    void keepsTheTwelveLatestExchanges() {
        JarvisFastMemory memory = new JarvisFastMemory(new JarvisFastContextBuilderTest.MovingClock(NOW));
        UUID alice = UUID.randomUUID();
        for (int i = 0; i < 15; i++) {
            memory.remember(alice, "parole " + i, "réponse " + i);
        }

        assertThat(memory.recall(alice)).hasSize(12);
        assertThat(memory.recall(alice).get(0).said()).isEqualTo("parole 3");
    }

    @Test
    void forgetsAfterThirtyMinutes() {
        JarvisFastContextBuilderTest.MovingClock clock = new JarvisFastContextBuilderTest.MovingClock(NOW);
        JarvisFastMemory memory = new JarvisFastMemory(clock);
        UUID alice = UUID.randomUUID();
        memory.remember(alice, "vieux", "oui");
        clock.now = NOW.plus(Duration.ofMinutes(31));
        memory.remember(alice, "récent", "oui");

        assertThat(memory.recall(alice)).extracting(JarvisFastMemory.Exchange::said).containsExactly("récent");
    }

    @Test
    void accountsAreSeparate() {
        JarvisFastMemory memory = new JarvisFastMemory(new JarvisFastContextBuilderTest.MovingClock(NOW));
        memory.remember(UUID.randomUUID(), "chez Alice", "oui");

        assertThat(memory.recall(UUID.randomUUID())).isEmpty();
    }
}
