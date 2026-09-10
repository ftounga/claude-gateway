package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * F-57 / SF-57-02 — la sonde TLS ne décide de rien.
 *
 * <p>Elle produit un diagnostic optionnel : tout ce qui tourne mal doit se résoudre par le silence,
 * jamais par un démarrage qui échoue. C'est la raison d'être de ces tests.</p>
 */
class TlsProbeTest {

    private static final String PUBLIC_ROOT = "CN=ISRG Root X1, O=Internet Security Research Group";
    private static final String CORPORATE_ROOT = "CN=Acme Corp Proxy CA, O=Acme";

    @Test
    void a_plain_http_gateway_is_never_probed() {
        AtomicBoolean called = new AtomicBoolean(false);
        TlsProbe probe = new TlsProbe(Set.of(PUBLIC_ROOT), target -> {
            called.set(true);
            return List.of();
        });

        assertEquals(Optional.empty(), probe.inspect("http://localhost:8080/api"));
        assertFalse(called.get(), "il n'y a pas de chaîne TLS à lire sur une gateway en clair");
    }

    @Test
    void a_failing_probe_stays_silent() {
        TlsProbe probe = new TlsProbe(Set.of(PUBLIC_ROOT), target -> {
            throw new java.io.IOException("connexion coupée");
        });

        assertEquals(Optional.empty(), probe.inspect("https://portal.example.com/api"),
                "un diagnostic optionnel n'a le droit de rien casser");
    }

    @Test
    void a_public_chain_produces_no_line() {
        TlsProbe probe = new TlsProbe(Set.of(PUBLIC_ROOT), target -> List.of(
                new TlsInspection.ChainLink("CN=portal.example.com", PUBLIC_ROOT)));

        assertEquals(Optional.empty(), probe.inspect("https://portal.example.com/api"));
    }

    @Test
    void an_intercepted_chain_produces_the_diagnostic_line_naming_the_host() {
        TlsProbe probe = new TlsProbe(Set.of(PUBLIC_ROOT), target -> List.of(
                new TlsInspection.ChainLink("CN=portal.example.com", CORPORATE_ROOT)));

        Optional<String> line = probe.inspect("https://portal.example.com/api");

        assertTrue(line.isPresent(), "une racine non publique est un diagnostic utile");
        assertTrue(line.get().contains("portal.example.com"), line.get());
        assertTrue(line.get().contains("Acme Corp Proxy CA"), line.get());
        assertTrue(line.get().contains("ne le contourne pas"), line.get());
    }

    @Test
    void a_malformed_gateway_url_stays_silent() {
        TlsProbe probe = new TlsProbe(Set.of(PUBLIC_ROOT), target -> List.of(
                new TlsInspection.ChainLink("CN=x", CORPORATE_ROOT)));

        assertEquals(Optional.empty(), probe.inspect(":::pas une url"));
    }

    @Test
    void the_jdk_root_store_is_readable_or_the_probe_simply_says_nothing() {
        // Le magasin du JDK est lu comme une liste publique de racines. Sur un JDK ordinaire il est
        // là et non vide ; sur une image sans magasin, l'ensemble est vide et la sonde se tait.
        // Les deux issues sont acceptables — ce qui ne l'est pas, c'est une exception.
        Set<String> roots = TlsProbe.publicRootsFromJdk();

        assertTrue(roots != null, "publicRootsFromJdk ne rend jamais null");
    }
}
