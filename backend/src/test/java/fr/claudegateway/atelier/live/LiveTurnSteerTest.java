package fr.claudegateway.atelier.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * La file des précisions <b>vit dans le tour vivant</b> (F-84 / SF-84-06).
 *
 * <p>Décision du PO du 2026-09-13 : un message envoyé pendant un tour est une précision ajoutée à ce
 * tour — jamais un second tour, jamais une remarque perdue en silence.</p>
 */
class LiveTurnSteerTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID PROJET = UUID.randomUUID();

    private final LiveTurnRegistry registry = new LiveTurnRegistry(new ObjectMapper());

    @Test
    void deposerUnePrecisionLAnnonceDansLeTour() {
        LiveTurn turn = registry.open(ALICE, PROJET);
        Recorder vue = new Recorder();
        turn.attach(vue, LiveTurn.FROM_START);

        SteerReceipt receipt = turn.offerSteer("en fait, saute les tests");

        assertThat(receipt.status()).isEqualTo(SteerReceipt.Status.ACCEPTED);
        assertThat(receipt.turnId()).isEqualTo(turn.turnId());
        assertThat(receipt.steerId()).isNotBlank();
        assertThat(vue.names()).containsExactly(LiveTurn.STEER_QUEUED);
        assertThat(vue.jsons().get(0))
                .contains("\"steerId\":\"" + receipt.steerId() + "\"")
                .contains("saute les tests");
    }

    @Test
    void lesPrecisionsSontPrisesDansLOrdreEtUneSeuleFois() {
        LiveTurn turn = registry.open(ALICE, PROJET);
        turn.offerSteer("première");
        turn.offerSteer("seconde");

        assertThat(turn.takeSteers()).extracting(LiveTurn.Steer::text)
                .containsExactly("première", "seconde");
        assertThat(turn.takeSteers()).as("prises, elles ne reviennent pas").isEmpty();
    }

    /**
     * SF-121-11 — le cap est assoupli (5 → 10) : ces dix-là attendent séparément, sans refus.
     */
    @Test
    void dixPrecisionsAttendentSeparement() {
        LiveTurn turn = registry.open(ALICE, PROJET);
        for (int i = 0; i < LiveTurn.MAX_PENDING_STEERS; i++) {
            assertThat(turn.offerSteer("précision " + i).status())
                    .isEqualTo(SteerReceipt.Status.ACCEPTED);
        }

        assertThat(LiveTurn.MAX_PENDING_STEERS).isEqualTo(10);
        assertThat(turn.takeSteers()).hasSize(LiveTurn.MAX_PENDING_STEERS);
    }

    /**
     * SF-121-11 — au-delà du cap, la précision n'est plus REFUSÉE : elle est <b>fondue</b> dans la
     * dernière en attente, qui garde son identifiant. L'utilisateur qui pense à voix haute n'est
     * pas rabroué pour un problème de comptage.
     */
    @Test
    void auDelaDuCapLaPrecisionEstFondueDansLaDerniere() {
        LiveTurn turn = registry.open(ALICE, PROJET);
        String lastId = null;
        for (int i = 0; i < LiveTurn.MAX_PENDING_STEERS; i++) {
            lastId = turn.offerSteer("précision " + i).steerId();
        }

        SteerReceipt coalesced = turn.offerSteer("et aussi : garde le dossier dist");

        assertThat(coalesced.status()).isEqualTo(SteerReceipt.Status.ACCEPTED);
        assertThat(coalesced.steerId()).as("même identifiant que la dernière en attente")
                .isEqualTo(lastId);
        assertThat(turn.live()).isTrue();
        List<LiveTurn.Steer> pending = turn.takeSteers();
        assertThat(pending).as("la file ne grandit pas").hasSize(LiveTurn.MAX_PENDING_STEERS);
        assertThat(pending.get(pending.size() - 1).text())
                .isEqualTo("précision 9\net aussi : garde le dossier dist");
    }

    /**
     * SF-121-11 — le vrai plafond est le VOLUME : ce qui coûte, c'est le texte ajouté à la
     * conversation. Au-delà, {@code FULL} — et le tour n'est pas touché.
     */
    @Test
    void auDelaDuVolumeCumuleLaPrecisionEstRefuseeSansToucherAuTour() {
        LiveTurn turn = registry.open(ALICE, PROJET);
        String pave = "x".repeat(LiveTurn.MAX_PENDING_STEER_CHARS - 10);
        assertThat(turn.offerSteer(pave).status()).isEqualTo(SteerReceipt.Status.ACCEPTED);

        assertThat(turn.offerSteer("onze caractères et plus encore").status())
                .isEqualTo(SteerReceipt.Status.FULL);
        assertThat(turn.live()).as("le tour n'est pas touché").isTrue();
        assertThat(turn.takeSteers()).as("la file est inchangée").hasSize(1);
    }

    @Test
    void unTourQuiRendSaReponseSansPrecisionSeScelle() {
        LiveTurn turn = registry.open(ALICE, PROJET);

        assertThat(turn.pollFollowUpOrSeal()).isEmpty();

        assertThat(turn.sealed()).isTrue();
        assertThat(turn.offerSteer("trop tard").status())
                .as("un tour scellé ne prend plus rien : l'envoi ouvrira un tour neuf")
                .isEqualTo(SteerReceipt.Status.ENDED);
    }

    @Test
    void unePrecisionArriveePendantLaReponseFinaleOuvreLeTourDeSuite() {
        LiveTurn turn = registry.open(ALICE, PROJET);
        turn.offerSteer("et ajoute un test");
        turn.offerSteer("et le changelog");

        assertThat(turn.pollFollowUpOrSeal()).map(LiveTurn.Steer::text).contains("et ajoute un test");
        assertThat(turn.sealed()).as("un tour de suite part : le tour n'est pas scellé").isFalse();
        assertThat(turn.takeSteers()).extracting(LiveTurn.Steer::text)
                .as("les suivantes restent en file, pour l'étape 1 du tour de suite")
                .containsExactly("et le changelog");
    }

    @Test
    void sceller_et_vider_rend_les_precisions_non_lues() {
        LiveTurn turn = registry.open(ALICE, PROJET);
        turn.offerSteer("inutile désormais");

        assertThat(turn.sealAndDrain()).extracting(LiveTurn.Steer::text)
                .containsExactly("inutile désormais");
        assertThat(turn.sealed()).isTrue();
        assertThat(turn.offerSteer("encore").status()).isEqualTo(SteerReceipt.Status.ENDED);
    }

    @Test
    void unePrecisionPendantUneAutorisationNeTrancheRien() {
        LiveTurn turn = registry.open(ALICE, PROJET);
        turn.publishApprovalRequest(new Payload("rm -rf build"),
                new PendingApproval("call-1", "bash", "rm -rf build", 120_000L,
                        System.currentTimeMillis()));

        turn.offerSteer("attends, garde le dossier dist");

        assertThat(turn.pendingApproval())
                .as("ni accord ni refus : l'attente reste en attente")
                .isPresent();
    }

    @Test
    void unTourTermineRefuseLesPrecisions() {
        LiveTurn turn = registry.open(ALICE, PROJET);
        registry.close(turn);

        assertThat(turn.offerSteer("trop tard").status()).isEqualTo(SteerReceipt.Status.ENDED);
    }

    // ------------------------------------------------------------- registre : précision ou tour

    @Test
    void envoyerPendantUnTourVivantDeposeUnePrecisionSansRemplacerLeTour() {
        LiveTurn vivant = registry.open(ALICE, PROJET);

        LiveTurnRegistry.Entry entry = registry.openOrSteer(ALICE, PROJET, "précise ceci");

        assertThat(entry.steered()).isTrue();
        assertThat(entry.turn()).isSameAs(vivant);
        assertThat(vivant.live()).isTrue();
        assertThat(registry.find(ALICE, PROJET)).contains(vivant);
        assertThat(vivant.takeSteers()).extracting(LiveTurn.Steer::text).containsExactly("précise ceci");
    }

    @Test
    void envoyerSurUnTourScelleOuvreUnTourNeuf() {
        LiveTurn ancien = registry.open(ALICE, PROJET);
        ancien.pollFollowUpOrSeal();

        LiveTurnRegistry.Entry entry = registry.openOrSteer(ALICE, PROJET, "nouvelle demande");

        assertThat(entry.steered()).isFalse();
        assertThat(entry.receipt()).isNull();
        assertThat(entry.turn()).isNotSameAs(ancien);
    }

    @Test
    void envoyerSansTourOuvreUnTour() {
        LiveTurnRegistry.Entry entry = registry.openOrSteer(ALICE, PROJET, "vas-y");

        assertThat(entry.steered()).isFalse();
        assertThat(registry.find(ALICE, PROJET)).contains(entry.turn());
    }

    @Test
    void unePrecisionDeBobNeTombeJamaisDansLeTourDAlice() {
        LiveTurn alice = registry.open(ALICE, PROJET);

        LiveTurnRegistry.Entry bob = registry.openOrSteer(BOB, PROJET, "je précise");

        assertThat(bob.steered()).isFalse();
        assertThat(bob.turn()).isNotSameAs(alice);
        assertThat(alice.live()).isTrue();
        assertThat(alice.takeSteers()).as("rien chez ALICE").isEmpty();
    }

    /**
     * File saturée <b>en volume</b> (SF-121-11 : c'est le seul vrai plafond) : le refus ne crée
     * jamais un second tour sur le même projet.
     */
    @Test
    void uneFilePleineNOuvrePasDeSecondTour() {
        LiveTurn vivant = registry.open(ALICE, PROJET);
        vivant.offerSteer("x".repeat(LiveTurn.MAX_PENDING_STEER_CHARS));

        LiveTurnRegistry.Entry entry = registry.openOrSteer(ALICE, PROJET, "une de trop");

        assertThat(entry.turn()).isSameAs(vivant);
        assertThat(entry.receipt().status()).isEqualTo(SteerReceipt.Status.FULL);
        assertThat(vivant.live()).isTrue();
    }

    /**
     * Le cap de <b>comptage</b>, lui, n'ouvre plus rien non plus — il coalesce (SF-121-11).
     */
    @Test
    void auDelaDuCapLOuvertureCoalesceDansLeTourVivant() {
        LiveTurn vivant = registry.open(ALICE, PROJET);
        for (int i = 0; i < LiveTurn.MAX_PENDING_STEERS; i++) {
            vivant.offerSteer("précision " + i);
        }

        LiveTurnRegistry.Entry entry = registry.openOrSteer(ALICE, PROJET, "une de plus");

        assertThat(entry.turn()).isSameAs(vivant);
        assertThat(entry.receipt().status()).isEqualTo(SteerReceipt.Status.ACCEPTED);
        assertThat(vivant.takeSteers()).hasSize(LiveTurn.MAX_PENDING_STEERS);
    }

    record Payload(String detail) {
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
            // rien
        }

        List<String> names() {
            return received.stream().map(TurnEvent::name).toList();
        }

        List<String> jsons() {
            return received.stream().map(TurnEvent::json).toList();
        }
    }
}
