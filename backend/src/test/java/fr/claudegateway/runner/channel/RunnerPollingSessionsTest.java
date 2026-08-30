package fr.claudegateway.runner.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.RunnerIdentity;

/**
 * Tests du cycle de vie des canaux de repli long-polling (F-38 / SF-38-09).
 *
 * <p>L'enjeu n'est pas d'ouvrir un canal : c'est que sa <b>présence</b> reste exacte. Un canal qui
 * s'enregistre mal fait mentir {@code GET /workspaces/{id}/runner/status} ; un canal qui se
 * désenregistre trop largement efface la connexion d'un runner qui vient de se reconnecter.</p>
 */
class RunnerPollingSessionsTest {

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID tokenId = UUID.randomUUID();
    private final RunnerIdentity identity = new RunnerIdentity(tokenId, userId, workspaceId);

    private InMemoryRunnerRegistry registry;
    private RunnerCallDispatcher dispatcher;
    private RunnerPollingSessions sessions;

    @BeforeEach
    void setUp() {
        registry = new InMemoryRunnerRegistry();
        dispatcher = new RunnerCallDispatcher(registry, new ObjectMapper(), 100L);
        sessions = new RunnerPollingSessions(registry, dispatcher, 60_000L);
    }

    @Test
    void firstPollRegistersThePresenceAndAttachesTheChannel() throws Exception {
        LongPollingRunnerOutbound channel = sessions.open(identity);

        assertThat(registry.findLocal(workspaceId)).isPresent();
        assertThat(registry.findLocal(workspaceId).orElseThrow().tokenId()).isEqualTo(tokenId);
        assertThat(registry.isConnected(workspaceId)).isTrue();
        // Branché sur le dispatcher : un tool_call part dans la file au lieu d'une socket.
        assertThat(channel.isOpen()).isTrue();
    }

    @Test
    void secondPollOfTheSameTokenReusesTheSameChannel() {
        LongPollingRunnerOutbound first = sessions.open(identity);
        LongPollingRunnerOutbound second = sessions.open(identity);

        // Réutilisation stricte : une trame mise en file entre deux polls ne doit pas être perdue.
        assertThat(second).isSameAs(first);
    }

    @Test
    void aDifferentTokenReplacesAndClosesThePreviousChannel() {
        LongPollingRunnerOutbound first = sessions.open(identity);

        UUID otherTokenId = UUID.randomUUID();
        LongPollingRunnerOutbound second =
                sessions.open(new RunnerIdentity(otherTokenId, userId, workspaceId));

        assertThat(second).isNotSameAs(first);
        assertThat(first.isOpen()).isFalse();
        assertThat(second.isOpen()).isTrue();
        // La présence enregistrée est celle du canal vivant, pas celle qu'on vient de fermer.
        assertThat(registry.findLocal(workspaceId).orElseThrow().tokenId()).isEqualTo(otherTokenId);
    }

    @Test
    void closingRemovesTheOwnPresenceOnly() {
        sessions.open(identity);

        assertThat(sessions.close(identity)).isTrue();

        assertThat(registry.findLocal(workspaceId)).isEmpty();
        assertThat(sessions.find(identity)).isEmpty();
        // Idempotent : raccrocher deux fois ne casse rien.
        assertThat(sessions.close(identity)).isFalse();
    }

    @Test
    void anotherTokenCannotHangUpThisRunnersChannel() {
        LongPollingRunnerOutbound mine = sessions.open(identity);

        boolean closed = sessions.close(new RunnerIdentity(UUID.randomUUID(), userId, workspaceId));

        assertThat(closed).isFalse();
        assertThat(mine.isOpen()).isTrue();
        assertThat(registry.findLocal(workspaceId)).isPresent();
    }

    @Test
    void closingDoesNotEraseAPresenceThatIsNoLongerOurs() {
        LongPollingRunnerOutbound mine = sessions.open(identity);

        // Un runner s'est reconnecté entre-temps (WebSocket, même jeton) : sa présence remplace la
        // nôtre. Notre fermeture tardive ne doit pas l'effacer — c'est exactement la course que la
        // garde anti-course évite.
        RunnerConnection reconnected = new RunnerConnection(workspaceId, userId, tokenId, "node-ws",
                java.time.OffsetDateTime.now());
        registry.register(reconnected);

        mine.close();

        assertThat(registry.findLocal(workspaceId)).contains(reconnected);
    }

    @Test
    void idleChannelsAreSweptAwayLikeADeadSocket() throws Exception {
        RunnerPollingSessions impatient = new RunnerPollingSessions(registry, dispatcher, 1L);
        LongPollingRunnerOutbound channel = impatient.open(identity);
        Thread.sleep(20);

        impatient.sweepIdleChannels();

        assertThat(channel.isOpen()).isFalse();
        assertThat(registry.findLocal(workspaceId)).isEmpty();
    }
}
