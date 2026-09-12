package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-80 / SF-80-01 — <b>où</b> le diagnostic est appelé, et ce qu'il n'a pas le droit de toucher.
 *
 * <p>Le défaut corrigé est un défaut de <b>placement</b> : la sonde de F-57 vivait après le
 * {@code return 5} du contrôle de vol, donc elle était inatteignable dans le seul cas qui la
 * justifie. Ces tests tiennent le placement <i>et</i> sa contrepartie : la lecture non validante ne
 * sert qu'au diagnostic, et le canal de trafic reste vérifié par la JVM.</p>
 */
class RunnerTlsDiagnosisTest {

    private static final String PUBLIC_ROOT = "CN=ISRG Root X1, O=Internet Security Research Group";
    private static final String GATEWAY = "https://portal.ng-itconsulting.com/api";

    @Test
    @DisplayName("un echec TLS nomme l'emetteur, la ou le PO n'avait qu'un PKIX brut")
    void aTlsFailureNamesTheIssuer() {
        TlsProbe probe = new TlsProbe(Set.of(PUBLIC_ROOT), target -> List.of(
                new TlsInspection.ChainLink(
                        "CN=portal.ng-itconsulting.com, O=Zscaler Inc.",
                        "CN=Zscaler Intermediate Root CA (zscaler.net) (t)")));
        NetworkPreflight.Verdict verdict =
                new NetworkPreflight.Verdict("La gateway n'est pas joignable", true);

        // L'observation est faite UNE fois, au démarrage (SF-80-02, D3), et réutilisée ici.
        Optional<String> lines =
                RunnerMain.handshakeDiagnosis(verdict, probe.observe(GATEWAY));

        assertTrue(lines.isPresent());
        assertTrue(lines.get().contains("Zscaler Inc."), lines.get());
    }

    @Test
    @DisplayName("un echec qui n'est pas TLS ne sonde RIEN")
    void aNonTlsFailureNeverProbes() {
        // Un DNS muet ou un port fermé ne laissent aucun certificat à lire : sonder y coûterait un
        // délai d'attente complet pour n'afficher aucune ligne.
        AtomicBoolean used = new AtomicBoolean(false);
        TlsProbe probe = new TlsProbe(Set.of(PUBLIC_ROOT), target -> List.of(
                new TlsInspection.ChainLink("CN=portal, O=Zscaler Inc.", "CN=Zscaler Root CA")));
        Optional<TlsProbe.Seen> seen = probe.observe(GATEWAY).map(value -> {
            used.set(true);
            return value;
        });
        used.set(false);
        NetworkPreflight.Verdict verdict = new NetworkPreflight.Verdict("DNS muet", false);

        assertEquals(Optional.empty(), RunnerMain.handshakeDiagnosis(verdict, seen));
        assertFalse(used.get(), "hors d'un échec TLS, l'observation ne doit pas être exploitée");
    }

    @Test
    @DisplayName("le canal de trafic n'herite JAMAIS de la confiance de diagnostic")
    void theTrafficChannelNeverInheritsTheDiagnosticTrust() throws Exception {
        // LA frontière qui rend la sonde acceptable. Le client par lequel passent l'appairage, les
        // trames et les résultats d'outils garde le truststore de la JVM — quoi qu'il arrive à côté.
        assertSame(SSLContext.getDefault(),
                RunnerMain.buildHttpClient(ProxyResolver.fromEnv(Map.of())).sslContext());
        assertSame(SSLContext.getDefault(),
                RunnerMain.buildHttpClient(
                        ProxyResolver.fromEnv(Map.of("HTTPS_PROXY", "http://proxy.exemple.fr:8080")))
                        .sslContext());
    }
}
