package fr.claudegateway.runner.relay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Résolution des pods pairs (F-38 / SF-38-13).
 *
 * <p>Ce que ces tests protègent : un pod ne se diffuse jamais un geste à lui-même (il a déjà agi
 * localement), et une résolution impossible n'est pas une erreur — elle décrit le cas mono-pod, qui
 * reste le cas courant.</p>
 */
class RelayPeerResolverTest {

    private static final String SECRET = "secret-de-relais-de-test-32-octets!!";

    private RunnerRelayProperties properties(String peersHost, String selfAddress, String secret) {
        RunnerRelayProperties properties = new RunnerRelayProperties();
        properties.setSecret(secret);
        properties.setPeersHost(peersHost);
        properties.setSelfAddress(selfAddress);
        properties.setPort(8081);
        return properties;
    }

    @Test
    void aDisabledRelayResolvesNoPeerAtAll() {
        RelayPeerResolver resolver = new RelayPeerResolver(properties("localhost", "", ""));

        assertThat(resolver.peerBaseUrls()).isEmpty();
    }

    @Test
    void anEmptyPeersHostResolvesNoPeer() {
        RelayPeerResolver resolver = new RelayPeerResolver(properties("  ", "", SECRET));

        assertThat(resolver.peerBaseUrls()).isEmpty();
    }

    @Test
    void anUnresolvableHostIsNotAnErrorAndYieldsNoPeer() {
        RelayPeerResolver resolver = new RelayPeerResolver(
                properties("hote-qui-n-existe-pas.invalid", "", SECRET));

        assertThat(resolver.peerBaseUrls()).isEmpty();
    }

    @Test
    void resolvedAddressesBecomeInternalConnectorUrls() {
        RelayPeerResolver resolver = new RelayPeerResolver(properties("localhost", "10.0.0.9", SECRET));

        assertThat(resolver.peerBaseUrls()).contains("http://127.0.0.1:8081");
    }

    @Test
    void aPodNeverBroadcastsToItself() {
        // Sa propre POD_IP est retirée : le geste local a déjà été appliqué avant la diffusion.
        RelayPeerResolver resolver = new RelayPeerResolver(properties("localhost", "127.0.0.1", SECRET));

        assertThat(resolver.peerBaseUrls()).doesNotContain("http://127.0.0.1:8081");
    }
}
