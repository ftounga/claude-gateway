package fr.claudegateway.atelier.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Le battement de cœur des tours vivants (F-170 / SF-170-01) : un tic pingue chaque tour vivant du pod,
 * jamais un tour terminé ; chaque tour ne pingue que ses propres spectateurs (isolation) ; un intervalle
 * {@code <= 0} désarme le battement sans erreur.
 */
class TurnHeartbeatTest {

    private final LiveTurnRegistry registry = new LiveTurnRegistry(new ObjectMapper());

    @Test
    void unTicPingueTousLesToursVivants() {
        LiveTurn a = registry.open(UUID.randomUUID(), UUID.randomUUID());
        LiveTurn b = registry.open(UUID.randomUUID(), UUID.randomUUID());
        PingRecorder specA = new PingRecorder();
        PingRecorder specB = new PingRecorder();
        a.attach(specA, LiveTurn.FROM_START);
        b.attach(specB, LiveTurn.FROM_START);

        new TurnHeartbeat(registry, 15_000L).beat();

        assertThat(specA.pings).as("le tour d'un utilisateur est pingué sur SON spectateur").isEqualTo(1);
        assertThat(specB.pings).as("le tour de l'autre utilisateur aussi, sur LE SIEN").isEqualTo(1);
    }

    @Test
    void unTourTermineNEstPasPingue() {
        LiveTurn vivant = registry.open(UUID.randomUUID(), UUID.randomUUID());
        LiveTurn fini = registry.open(UUID.randomUUID(), UUID.randomUUID());
        PingRecorder specVivant = new PingRecorder();
        PingRecorder specFini = new PingRecorder();
        vivant.attach(specVivant, LiveTurn.FROM_START);
        fini.attach(specFini, LiveTurn.FROM_START);
        registry.close(fini);

        new TurnHeartbeat(registry, 15_000L).beat();

        assertThat(specVivant.pings).isEqualTo(1);
        assertThat(specFini.pings).as("un tour clos ne reçoit plus de battement").isZero();
    }

    @Test
    void intervalleNulDesarmeLeBattementSansErreur() {
        TurnHeartbeat heartbeat = new TurnHeartbeat(registry, 0L);

        assertThatCode(heartbeat::start).doesNotThrowAnyException();
        assertThatCode(heartbeat::stop).doesNotThrowAnyException();
    }

    /** Un spectateur qui ne compte que ses battements. */
    private static final class PingRecorder implements TurnSubscriber {

        private int pings;

        @Override
        public boolean deliver(TurnEvent event) {
            return true;
        }

        @Override
        public void finish() {
        }

        @Override
        public boolean heartbeat() {
            pings++;
            return true;
        }
    }
}
