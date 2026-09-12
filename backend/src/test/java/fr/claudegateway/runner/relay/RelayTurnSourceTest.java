package fr.claudegateway.runner.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.live.TurnEvent;
import fr.claudegateway.atelier.live.TurnSubscriber;

/**
 * La dégradation du relais de tour (F-84 / SF-84-02) : sans relais possible, on ne dit jamais qu'un
 * tour existe ailleurs.
 *
 * <p>C'est le garde-fou qui empêche un écran de croire qu'il regarde un tour alors qu'il ne regarde
 * rien : le mensonge serait pire que l'absence.</p>
 */
class RelayTurnSourceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID PROJET = UUID.randomUUID();

    @Test
    void relaisEteintNeDitJamaisQuUnTourExiste() {
        RelayTurnSource source = RelayTurnSource.disabled();

        assertThat(source.findRemoteTurn(USER, PROJET)).isEmpty();
        assertThat(source.streamRemoteTurn(USER, PROJET, 0L, new NeverCalled())).isFalse();
    }

    @Test
    void aucunPairResoluNeDitJamaisQuUnTourExiste() {
        RunnerRelayProperties properties = new RunnerRelayProperties();
        properties.setSecret("secret-de-relais-de-test-32-octets!!");
        // Hôte de pairs introuvable : le résolveur rend une liste vide, comme un pod seul.
        properties.setPeersHost("pair-qui-n-existe-pas.invalid");
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        RelayTurnSource source = new RelayTurnSource(properties, new RelayPeerResolver(properties),
                new RelayPeerClient(properties, mapper), mapper);

        assertThat(source.findRemoteTurn(USER, PROJET)).isEmpty();
        assertThat(source.streamRemoteTurn(USER, PROJET, 0L, new NeverCalled())).isFalse();
    }

    /** Un spectateur qui ne doit jamais rien recevoir : le recevoir serait l'anomalie. */
    private static final class NeverCalled implements TurnSubscriber {

        @Override
        public boolean deliver(TurnEvent event) {
            throw new AssertionError("aucun événement ne doit être relayé sans pair propriétaire");
        }

        @Override
        public void finish() {
            throw new AssertionError("aucun flux relayé ne doit se clore : il n'y en a pas eu");
        }
    }
}
