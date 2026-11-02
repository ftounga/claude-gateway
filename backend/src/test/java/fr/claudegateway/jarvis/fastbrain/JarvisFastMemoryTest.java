package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.AtelierMessage;

/**
 * F-235 / SF-235-02, F-239 / SF-239-09 — la mémoire de conversation : la journée (depuis minuit à Paris), 40 échanges
 * au plus, comptes séparés, réhydratée une fois par jour depuis le fil.
 */
class JarvisFastMemoryTest {

    /** Lundi 2 novembre 2026, 9 h 00 à Paris. */
    private static final Instant NINE = Instant.parse("2026-11-02T08:00:00Z");

    @Test
    void keepsTheFortyLatestExchanges() {
        JarvisFastMemory memory = new JarvisFastMemory(new JarvisFastContextBuilderTest.MovingClock(NINE));
        UUID alice = UUID.randomUUID();
        for (int i = 0; i < 45; i++) {
            memory.remember(alice, "parole " + i, "réponse " + i);
        }

        assertThat(memory.recall(alice)).hasSize(40);
        assertThat(memory.recall(alice).get(0).said()).isEqualTo("parole 5");
    }

    @Test
    void remembersTheWholeDayAndForgetsAtMidnight() {
        JarvisFastContextBuilderTest.MovingClock clock = new JarvisFastContextBuilderTest.MovingClock(NINE);
        JarvisFastMemory memory = new JarvisFastMemory(clock);
        UUID alice = UUID.randomUUID();
        memory.remember(alice, "à 9 h", "oui");
        clock.now = NINE.plus(Duration.ofHours(6));
        memory.remember(alice, "à 15 h", "oui");
        assertThat(memory.recall(alice)).extracting(JarvisFastMemory.Exchange::said).containsExactly("à 9 h", "à 15 h");

        clock.now = NINE.plus(Duration.ofHours(16)); // 1 h du matin, le lendemain
        assertThat(memory.recall(alice)).isEmpty();
    }

    @Test
    void accountsAreSeparate() {
        JarvisFastMemory memory = new JarvisFastMemory(new JarvisFastContextBuilderTest.MovingClock(NINE));
        memory.remember(UUID.randomUUID(), "chez Alice", "oui");

        assertThat(memory.recall(UUID.randomUUID())).isEmpty();
    }

    @Test
    void afterARestartTheDayIsReloadedOnceFromTheAccountsThread() {
        JarvisFastMemory memory = new JarvisFastMemory(new JarvisFastContextBuilderTest.MovingClock(NINE));
        UUID alice = UUID.randomUUID();
        List<UUID> asked = new ArrayList<>();
        memory.setLoader((userId, dayStart) -> {
            asked.add(userId);
            assertThat(dayStart).isEqualTo(Instant.parse("2026-11-01T23:00:00Z"));
            return List.of(new JarvisFastMemory.Exchange(NINE.minusSeconds(600), "Bonjour Jarvis.", "Bonjour, Monsieur."));
        });

        assertThat(memory.recall(alice)).extracting(JarvisFastMemory.Exchange::said).containsExactly("Bonjour Jarvis.");
        memory.recall(alice);
        memory.remember(alice, "Où en est Kestrel ?", "Aux tests.");
        assertThat(asked).containsExactly(alice);
        assertThat(memory.recall(alice)).hasSize(2);
    }

    @Test
    void theLoaderPairsEachWordWithTheAnswerThatFollowsAndKeepsOnlyTheSpokenLine() {
        OffsetDateTime t = OffsetDateTime.of(2026, 11, 2, 8, 0, 0, 0, ZoneOffset.UTC);
        List<AtelierMessage> rows = List.of(
                row("USER", "Bonjour Jarvis.", t), row("ASSISTANT", "Bonjour, Monsieur.", t.plusSeconds(2)),
                row("USER", "Regarde les tickets.", t.plusSeconds(30)),
                row("ASSISTANT", "🎙 Deux tickets bloquent.\n\nDétail : …", t.plusSeconds(60)),
                row("USER", "Merci.", t.plusSeconds(90)));

        List<JarvisFastMemory.Exchange> exchanges = JarvisFastMemoryLoader.pair(rows);

        assertThat(exchanges).extracting(JarvisFastMemory.Exchange::said)
                .containsExactly("Bonjour Jarvis.", "Regarde les tickets.", "Merci.");
        assertThat(exchanges).extracting(JarvisFastMemory.Exchange::answered)
                .containsExactly("Bonjour, Monsieur.", "Deux tickets bloquent.", "");
    }

    private static AtelierMessage row(String role, String content, OffsetDateTime at) {
        return AtelierMessage.builder().id(UUID.randomUUID()).role(role).content(content).createdAt(at)
                .via(AtelierMessage.VIA_JARVIS).build();
    }
}
