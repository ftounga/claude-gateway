package fr.claudegateway.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * « Le battement fait foi » (F-97 / SF-97-01) : une seule définition de la fraîcheur, lue partout.
 */
@ExtendWith(MockitoExtension.class)
class RunnerLivenessTest {

    @Mock
    private RunnerTokenRepository tokenRepository;

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private RunnerLiveness liveness() {
        return new RunnerLiveness(tokenRepository, Duration.ofSeconds(90));
    }

    @Test
    void aBeatInsideTheWindowIsFresh() {
        assertThat(liveness().isFresh(OffsetDateTime.now().minusSeconds(89))).isTrue();
    }

    @Test
    void aBeatOutsideTheWindowIsStale() {
        assertThat(liveness().isFresh(OffsetDateTime.now().minusSeconds(91))).isFalse();
    }

    @Test
    void neverSeenIsStale() {
        assertThat(liveness().isFresh(null)).isFalse();
    }

    @Test
    void aliveReadsTheOwnersHostOnly() {
        // Isolation : la lecture est filtrée par user_id ET host_id.
        when(tokenRepository.findLastSeenAt(userId, hostId))
                .thenReturn(OffsetDateTime.now().minusSeconds(10));

        assertThat(liveness().isAlive(userId, hostId)).isTrue();
        verify(tokenRepository).findLastSeenAt(userId, hostId);
    }

    @Test
    void aHostThatStoppedBeatingIsNotAlive() {
        when(tokenRepository.findLastSeenAt(userId, hostId))
                .thenReturn(OffsetDateTime.now().minusMinutes(3));

        assertThat(liveness().isAlive(userId, hostId)).isFalse();
    }

    @Test
    void routingReadsTheSameRule() {
        when(tokenRepository.findLastSeenAtForRouting(hostId)).thenReturn(null);

        assertThat(liveness().isAliveForRouting(hostId)).isFalse();
    }
}
