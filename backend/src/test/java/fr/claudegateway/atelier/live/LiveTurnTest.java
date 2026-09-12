package fr.claudegateway.atelier.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Le tampon ordonné d'un tour (F-84 / SF-84-01) : ordre, détachement, bornes, rejeu. */
class LiveTurnTest {

    private final LiveTurnRegistry registry = new LiveTurnRegistry(new ObjectMapper());

    private LiveTurn turn() {
        return registry.open(UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void deuxAbonnesRecoiventLaMemeSuite() {
        LiveTurn turn = turn();
        Recorder premier = new Recorder();
        Recorder second = new Recorder();
        turn.attach(premier, LiveTurn.FROM_START);
        turn.attach(second, LiveTurn.FROM_START);

        turn.publish("text", new Payload("un"));
        turn.publish("text", new Payload("deux"));

        assertThat(premier.names()).containsExactly("text", "text");
        assertThat(premier.jsons()).isEqualTo(second.jsons());
        assertThat(premier.seqs()).containsExactly(1L, 2L);
    }

    @Test
    void unAbonneEnEchecEstDetacheSansArreterLaPublication() {
        LiveTurn turn = turn();
        Recorder vivant = new Recorder();
        Recorder mort = new Recorder();
        mort.alive = false;
        turn.attach(vivant, LiveTurn.FROM_START);
        turn.attach(mort, LiveTurn.FROM_START);

        turn.publish("text", new Payload("un"));
        turn.publish("text", new Payload("deux"));

        assertThat(turn.subscriberCount()).as("le spectateur parti est détaché").isEqualTo(1);
        assertThat(vivant.names()).as("le tour continue de publier").hasSize(2);
        assertThat(turn.live()).as("le tour n'est pas arrêté par un spectateur parti").isTrue();
    }

    @Test
    void unAbonneMortDesLePremierEnvoiNeBrancheJamaisNiNArreteRien() {
        LiveTurn turn = turn();
        Recorder mort = new Recorder();
        mort.alive = false;
        turn.publish("text", new Payload("déjà publié"));

        boolean attached = turn.attach(mort, LiveTurn.FROM_START);

        assertThat(attached).isFalse();
        assertThat(turn.subscriberCount()).isZero();
        assertThat(turn.live()).isTrue();
    }

    @Test
    void leRejeuDepuisUnCurseurNeLivreQueCeQuiAEteManque() {
        LiveTurn turn = turn();
        turn.publish("text", new Payload("un"));
        turn.publish("text", new Payload("deux"));
        long vu = turn.cursor();
        turn.publish("text", new Payload("trois"));

        Recorder retour = new Recorder();
        turn.attach(retour, vu);

        assertThat(retour.seqs()).as("ni doublon, ni trou").containsExactly(3L);
    }

    @Test
    void leTamponEstBorneEnNombreEtLeTrouEstDit() {
        LiveTurn turn = turn();
        for (int i = 0; i < LiveTurn.MAX_EVENTS + 20; i++) {
            turn.publish("text", new Payload("ligne " + i));
        }

        assertThat(turn.droppedThrough()).as("les plus anciens sont partis").isEqualTo(20L);

        Recorder tardif = new Recorder();
        turn.attach(tardif, LiveTurn.FROM_START);

        assertThat(tardif.names().get(0))
                .as("un rejeu amputé le dit avant de rejouer")
                .isEqualTo(LiveTurn.TRUNCATED);
        assertThat(tardif.jsons().get(0)).contains("\"droppedThrough\":20");
        assertThat(tardif.names()).hasSize(LiveTurn.MAX_EVENTS + 1);
    }

    @Test
    void leTamponEstBorneEnTaille() {
        LiveTurn turn = turn();
        String gros = "x".repeat(100_000);
        for (int i = 0; i < 10; i++) {
            turn.publish("output", new Payload(gros));
        }

        assertThat(turn.droppedThrough())
                .as("la borne de caractères a fait défiler le tampon")
                .isGreaterThan(0L);
        Recorder tardif = new Recorder();
        turn.attach(tardif, LiveTurn.FROM_START);
        assertThat(tardif.names().get(0)).isEqualTo(LiveTurn.TRUNCATED);
    }

    @Test
    void unTourTermineNAcceptePlusDeSpectateur() {
        LiveTurn turn = turn();
        turn.finish();

        assertThat(turn.attach(new Recorder(), LiveTurn.FROM_START)).isFalse();
        assertThat(turn.live()).isFalse();
    }

    @Test
    void clore_previent_les_spectateurs_encore_branches() {
        LiveTurn turn = turn();
        Recorder spectateur = new Recorder();
        turn.attach(spectateur, LiveTurn.FROM_START);

        registry.close(turn);

        assertThat(spectateur.finished).isTrue();
    }

    /** Charge utile quelconque : seul compte qu'elle soit sérialisable. */
    private record Payload(String text) {
    }

    /** Un spectateur de test : il note ce qu'il reçoit, et peut décider d'être « parti ». */
    private static final class Recorder implements TurnSubscriber {

        private final List<TurnEvent> received = new ArrayList<>();
        private boolean alive = true;
        private boolean finished;

        @Override
        public boolean deliver(TurnEvent event) {
            if (!alive) {
                return false;
            }
            received.add(event);
            return true;
        }

        @Override
        public void finish() {
            finished = true;
        }

        List<String> names() {
            return received.stream().map(TurnEvent::name).toList();
        }

        List<String> jsons() {
            return received.stream().map(TurnEvent::json).toList();
        }

        List<Long> seqs() {
            return received.stream().map(TurnEvent::seq).toList();
        }
    }
}
