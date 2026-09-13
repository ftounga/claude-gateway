package fr.claudegateway.atelier.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * L'attache <b>par fenêtres</b> (F-84 / SF-84-04) : une réponse qui se clôt vite, pour qu'un proxy
 * d'entreprise qui retient le flux jusqu'à sa fin la relâche aussitôt.
 *
 * <p>Constat de production du 2026-09-13 : derrière Netskope, 29 appels d'outils exécutés, et le
 * terminal resté sur « démarrage… » pendant 492 s — le corps de la réponse n'a été relâché qu'à la
 * clôture du flux.</p>
 */
class WindowedTurnSubscriberTest {

    private final LiveTurnRegistry registry = new LiveTurnRegistry(new ObjectMapper());
    private final ManualTimer timer = new ManualTimer();

    @Test
    void seClotPeuApresLePremierEvenementDeTour() {
        LiveTurn turn = registry.open(UUID.randomUUID(), UUID.randomUUID());
        Recording ecran = new Recording();
        WindowedTurnSubscriber fenetre = WindowedTurnSubscriber.open(ecran, turn, timer, 10_000L);

        assertThat(turn.attach(fenetre, LiveTurn.FROM_START)).isTrue();
        turn.publish("action", new Payload("npm test"));

        assertThat(ecran.finished).as("la clôture attend un bref instant, pour regrouper une rafale")
                .isFalse();
        timer.fire(WindowedTurnSubscriber.LINGER_MS);

        assertThat(ecran.names).containsExactly("action");
        assertThat(ecran.finished).as("une réponse close est une réponse qu'un proxy relâche").isTrue();
        assertThat(turn.subscriberCount()).as("le spectateur est détaché").isZero();
        assertThat(turn.live()).as("le tour, lui, continue").isTrue();
    }

    @Test
    void unRejeuSuffitAClore() {
        LiveTurn turn = registry.open(UUID.randomUUID(), UUID.randomUUID());
        turn.publish("action", new Payload("ls"));
        Recording ecran = new Recording();
        WindowedTurnSubscriber fenetre = WindowedTurnSubscriber.open(ecran, turn, timer, 10_000L);

        turn.attach(fenetre, LiveTurn.FROM_START);
        timer.fire(WindowedTurnSubscriber.LINGER_MS);

        assertThat(ecran.names).containsExactly("action");
        assertThat(ecran.finished).isTrue();
    }

    @Test
    void unAparteNeDeclenchePasLaCloture() {
        LiveTurn turn = registry.open(UUID.randomUUID(), UUID.randomUUID());
        Recording ecran = new Recording();
        WindowedTurnSubscriber fenetre = WindowedTurnSubscriber.open(ecran, turn, timer, 10_000L);

        fenetre.deliver(TurnAsides.attached(turn.turnId(), 0L, 0L));
        turn.attach(fenetre, LiveTurn.FROM_START);
        timer.fire(WindowedTurnSubscriber.LINGER_MS);

        assertThat(ecran.finished)
                .as("un aparté (numéro 0) n'est pas un événement de tour : rien de neuf à relâcher")
                .isFalse();
    }

    @Test
    void seClotALEcheanceSansRienARecevoir() {
        LiveTurn turn = registry.open(UUID.randomUUID(), UUID.randomUUID());
        Recording ecran = new Recording();
        WindowedTurnSubscriber fenetre = WindowedTurnSubscriber.open(ecran, turn, timer, 5_000L);
        turn.attach(fenetre, LiveTurn.FROM_START);

        timer.fire(4_999L);
        assertThat(ecran.finished).isFalse();
        timer.fire(5_000L);

        assertThat(ecran.finished).isTrue();
        assertThat(turn.subscriberCount()).isZero();
    }

    @Test
    void apresClotureRienNePartPlus() {
        LiveTurn turn = registry.open(UUID.randomUUID(), UUID.randomUUID());
        Recording ecran = new Recording();
        WindowedTurnSubscriber fenetre = WindowedTurnSubscriber.open(ecran, turn, timer, 5_000L);
        turn.attach(fenetre, LiveTurn.FROM_START);
        turn.publish("action", new Payload("un"));
        timer.fire(WindowedTurnSubscriber.LINGER_MS);

        assertThat(fenetre.deliver(new TurnEvent(9L, "action", "{}"))).isFalse();
        fenetre.finish();

        assertThat(ecran.names).containsExactly("action");
        assertThat(ecran.finishCount).as("clore est idempotent").isEqualTo(1);
    }

    @Test
    void laFinDuTourCloreLaFenetreEtAnnuleSesMinuteurs() {
        LiveTurn turn = registry.open(UUID.randomUUID(), UUID.randomUUID());
        Recording ecran = new Recording();
        WindowedTurnSubscriber fenetre = WindowedTurnSubscriber.open(ecran, turn, timer, 5_000L);
        turn.attach(fenetre, LiveTurn.FROM_START);

        registry.close(turn);

        assertThat(ecran.finished).isTrue();
        assertThat(timer.pending()).as("aucun minuteur ne survit à une fenêtre close").isZero();
    }

    @Test
    void lEcheanceDemandeeEstBornee() {
        assertThat(WindowedTurnSubscriber.boundedWait(0L)).isEqualTo(WindowedTurnSubscriber.MIN_WAIT_MS);
        assertThat(WindowedTurnSubscriber.boundedWait(-5L)).isEqualTo(WindowedTurnSubscriber.MIN_WAIT_MS);
        assertThat(WindowedTurnSubscriber.boundedWait(3_000L)).isEqualTo(3_000L);
        assertThat(WindowedTurnSubscriber.boundedWait(3_600_000L))
                .isEqualTo(WindowedTurnSubscriber.MAX_WAIT_MS);
    }

    // ------------------------------------------------------------------ montage

    private record Payload(String text) {
    }

    /** Un écran qui note ce qu'il reçoit. */
    private static final class Recording implements TurnSubscriber {
        private final List<String> names = new ArrayList<>();
        private boolean finished;
        private int finishCount;

        @Override
        public boolean deliver(TurnEvent event) {
            if (finished) {
                return false;
            }
            names.add(event.name());
            return true;
        }

        @Override
        public void finish() {
            finished = true;
            finishCount++;
        }
    }

    /** Un minuteur qu'on avance à la main : aucun test ne dépend de l'horloge. */
    static final class ManualTimer implements WindowedTurnSubscriber.Timer {
        private final List<Task> tasks = new ArrayList<>();

        @Override
        public Runnable schedule(Runnable action, long delayMs) {
            Task task = new Task(action, delayMs);
            tasks.add(task);
            return () -> tasks.remove(task);
        }

        /** Exécute tout ce qui est dû à l'instant {@code atMs} depuis la programmation. */
        void fire(long atMs) {
            for (Task task : List.copyOf(tasks)) {
                if (task.delayMs <= atMs && tasks.remove(task)) {
                    task.action.run();
                }
            }
        }

        int pending() {
            return tasks.size();
        }

        private record Task(Runnable action, long delayMs) {
        }
    }
}
