package fr.claudegateway.help;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Garde-fou de débit de l'aide (F-54 / SF-54-01) : plafond par fenêtre glissante, compteur
 * <b>propre à chaque utilisateur</b>, et réouverture une fois la fenêtre écoulée.
 */
class HelpRateLimiterTest {

    /** Horloge pilotée : la fenêtre glissante se teste sans attendre. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-10T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
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

        void advance(Duration amount) {
            now = now.plus(amount);
        }
    }

    private static final HelpProperties PROPERTIES =
            new HelpProperties(3, Duration.ofMinutes(60), 512);

    @Test
    void refuseAuDelaDuPlafondDeLaFenetre() {
        HelpRateLimiter limiter = new HelpRateLimiter(PROPERTIES, new MutableClock());
        UUID user = UUID.randomUUID();

        limiter.acquire(user);
        limiter.acquire(user);
        limiter.acquire(user);

        assertThatThrownBy(() -> limiter.acquire(user))
                .isInstanceOf(HelpRateLimitExceededException.class);
    }

    @Test
    void leCompteurEstPropreAChaqueUtilisateur() {
        HelpRateLimiter limiter = new HelpRateLimiter(PROPERTIES, new MutableClock());
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        limiter.acquire(alice);
        limiter.acquire(alice);
        limiter.acquire(alice);

        // Alice a saturé sa fenêtre ; Bob n'est pas concerné.
        assertThatThrownBy(() -> limiter.acquire(alice))
                .isInstanceOf(HelpRateLimitExceededException.class);
        assertThatCode(() -> limiter.acquire(bob)).doesNotThrowAnyException();
    }

    @Test
    void rouvreUneFoisLaFenetreEcoulee() {
        MutableClock clock = new MutableClock();
        HelpRateLimiter limiter = new HelpRateLimiter(PROPERTIES, clock);
        UUID user = UUID.randomUUID();

        limiter.acquire(user);
        limiter.acquire(user);
        limiter.acquire(user);
        assertThatThrownBy(() -> limiter.acquire(user))
                .isInstanceOf(HelpRateLimitExceededException.class);

        clock.advance(Duration.ofMinutes(61));

        assertThatCode(() -> limiter.acquire(user)).doesNotThrowAnyException();
    }

    @Test
    void unReglageAberrantRetombeSurLesDefauts() {
        HelpProperties properties = new HelpProperties(0, Duration.ZERO, -1);

        org.assertj.core.api.Assertions.assertThat(properties.maxQuestions())
                .isEqualTo(HelpProperties.DEFAULT_MAX_QUESTIONS);
        org.assertj.core.api.Assertions.assertThat(properties.window())
                .isEqualTo(HelpProperties.DEFAULT_WINDOW);
        org.assertj.core.api.Assertions.assertThat(properties.maxTokens())
                .isEqualTo(HelpProperties.DEFAULT_MAX_TOKENS);
    }
}
