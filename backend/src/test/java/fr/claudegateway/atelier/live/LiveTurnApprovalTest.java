package fr.claudegateway.atelier.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * L'autorisation comme <b>état du tour</b> (F-84 / SF-84-03).
 *
 * <p>Ce qui se vérifie ici est le défaut rapporté le 2026-09-12 : « l'invite d'autorisation ne
 * s'affiche pas ». Il n'y avait très probablement <b>aucun défaut de rendu</b> — la demande partait
 * dans un flux mort, et personne ne la voyait jamais. Un état, lui, se retrouve.</p>
 */
class LiveTurnApprovalTest {

    private final LiveTurnRegistry registry = new LiveTurnRegistry(new ObjectMapper());

    private LiveTurn turn() {
        return registry.open(UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void uneDemandeEnAttenteEstUnEtatDuTour() {
        LiveTurn turn = turn();

        turn.publishApprovalRequest(new Payload("rm -rf build"), pending(120_000L, 0L));

        assertThat(turn.pendingApproval()).isPresent();
        assertThat(turn.pendingApproval().get().toolUseId()).isEqualTo("call-1");
    }

    @Test
    void unEcranArriveApresCoupVoitLattenteAvecSonTempsRestant() {
        LiveTurn turn = turn();
        turn.publish("text", new Payload("je réfléchis"));
        turn.publishApprovalRequest(new Payload("rm -rf build"), pending(120_000L, 0L));

        Recorder tardif = new Recorder();
        turn.attach(tardif, LiveTurn.FROM_START);

        assertThat(tardif.names())
                .as("le rejeu, puis l'état de l'attente")
                .containsExactly("text", "confirm_request", LiveTurn.CONFIRM_STATE);
        assertThat(tardif.jsons().get(2)).contains("\"toolUseId\":\"call-1\"");
        assertThat(tardif.seqs().get(2))
                .as("un aparté ne consomme aucun numéro d'ordre")
                .isZero();
    }

    @Test
    void leTempsRestantEstRecalculeJamaisLeDelaiDorigine() {
        LiveTurn turn = turn();
        // Demande posée il y a 100 s sur un délai de 120 s : il en reste 20, pas 120.
        turn.publishApprovalRequest(new Payload("rm -rf build"), pending(120_000L, 100_000L));

        Recorder tardif = new Recorder();
        turn.attach(tardif, LiveTurn.FROM_START);

        String state = tardif.jsons().get(tardif.jsons().size() - 1);
        long announced = Long.parseLong(state.replaceAll(".*\"timeoutMs\":(\\d+).*", "$1"));
        assertThat(announced)
                .as("le temps restant vient de la gateway, jamais le délai d'origine (SF-47-02)")
                .isLessThanOrEqualTo(20_000L)
                .isGreaterThan(15_000L);
    }

    @Test
    void uneDemandeTrancheeNestPlusUnEtat() {
        LiveTurn turn = turn();
        turn.publishApprovalRequest(new Payload("rm -rf build"), pending(120_000L, 0L));

        turn.publishApprovalResolved(new Payload("allow"), "call-1");

        assertThat(turn.pendingApproval()).isEmpty();
        Recorder tardif = new Recorder();
        turn.attach(tardif, LiveTurn.FROM_START);
        assertThat(tardif.names()).doesNotContain(LiveTurn.CONFIRM_STATE);
    }

    @Test
    void uneDemandeExpireeNestPlusAffichee() {
        LiveTurn turn = turn();
        // Posée il y a plus longtemps que son délai : elle ne peut plus être tranchée.
        turn.publishApprovalRequest(new Payload("rm -rf build"), pending(120_000L, 130_000L));

        assertThat(turn.pendingApproval()).isEmpty();
        Recorder tardif = new Recorder();
        turn.attach(tardif, LiveTurn.FROM_START);
        assertThat(tardif.names())
                .as("jamais une invite qui ne peut plus rien autoriser")
                .doesNotContain(LiveTurn.CONFIRM_STATE);
    }

    @Test
    void uneCommandeAvecDesGuillemetsResteDuJsonValide() {
        LiveTurn turn = turn();
        turn.publishApprovalRequest(new Payload("x"),
                new PendingApproval("call-1", "bash", "echo \"salut\"\nls\\", 120_000L,
                        System.currentTimeMillis()));

        Recorder tardif = new Recorder();
        turn.attach(tardif, LiveTurn.FROM_START);

        String state = tardif.jsons().get(tardif.jsons().size() - 1);
        assertThat(state).contains("\\\"salut\\\"").contains("\\n").contains("\\\\");
    }

    /** Une attente posée il y a {@code ageMs} millisecondes. */
    private static PendingApproval pending(long timeoutMs, long ageMs) {
        return new PendingApproval("call-1", "bash", "rm -rf build", timeoutMs,
                System.currentTimeMillis() - ageMs);
    }

    private record Payload(String text) {
    }

    private static final class Recorder implements TurnSubscriber {

        private final List<TurnEvent> received = new ArrayList<>();

        @Override
        public boolean deliver(TurnEvent event) {
            received.add(event);
            return true;
        }

        @Override
        public void finish() {
            // Rien à faire : ce spectateur ne vit que le temps du test.
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
