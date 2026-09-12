package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-80 / SF-80-02 — ce que le runner <b>dit</b> de sa confiance, et par où passe son trafic.
 *
 * <p>La décision du PO (OQ-17) est « automatique <b>et annoncé</b> ». L'automatisme sans l'annonce
 * serait un runner qui suit un équipement d'inspection sans que personne le sache : ces tests
 * tiennent la seconde moitié de la décision.</p>
 *
 * <p>Et la règle qui la borne : <b>aucune mention quand il n'y a rien à dire</b>. Le faux positif
 * est interdit (D2 de F-57) — une ligne qui crierait à l'interception là où il n'y en a pas
 * détruirait la confiance dans tous les autres messages du runner.</p>
 */
class SystemTrustDisclosureTest {

    private static final ProxyResolver.Route DIRECT = ProxyResolver.fromEnv(Map.of()).route();

    private static TrustStores.Trust systemStoreUsed() {
        return new TrustStores.Trust(silentContext(), null, true, 4,
                "/etc/ssl/certs/ca-certificates.crt");
    }

    @Test
    @DisplayName("la ligne Confiance nomme la racine d'entreprise constatee")
    void theTrustLineNamesTheEnterpriseRoot() {
        Optional<String> line = StartupDisclosure.trustLine(systemStoreUsed(), true,
                "Zscaler Inc. (CN=Zscaler Intermediate Root CA)");

        assertTrue(line.isPresent());
        assertTrue(line.get().startsWith("Confiance :"), line.get());
        assertTrue(line.get().contains("magasin de la JDK + magasin du système"), line.get());
        assertTrue(line.get().contains("/etc/ssl/certs/ca-certificates.crt"), line.get());
        assertTrue(line.get().contains("racine d'entreprise détectée : Zscaler Inc."), line.get());
    }

    @Test
    @DisplayName("aucune racine constatee : la ligne reste, la MENTION disparait")
    void withoutADetectedRootNothingIsNamed() {
        Optional<String> line = StartupDisclosure.trustLine(systemStoreUsed(), true, null);

        assertTrue(line.isPresent(), "la configuration se dit toujours");
        assertFalse(line.get().contains("racine d'entreprise"),
                "faux positif interdit (D2 de F-57) : " + line.get());
        assertFalse(StartupDisclosure.trustLine(systemStoreUsed(), true, "   ").get()
                .contains("racine d'entreprise"));
    }

    @Test
    @DisplayName("magasin systeme inutilisable : AUCUNE ligne — le repli est silencieux")
    void anUnusableSystemStoreSaysNothingAtAll() {
        // Conteneur minimal, magasin illisible, magasin qui n'apporte rien : trois cas NORMAUX. Ils
        // ne produisent ni erreur, ni avertissement, ni ligne (D4).
        assertEquals(Optional.empty(),
                StartupDisclosure.trustLine(TrustStores.Trust.jdkOnly(), true, null));
        assertEquals(Optional.empty(), StartupDisclosure.trustLine(null, true, "Zscaler Inc."));
    }

    @Test
    @DisplayName("--no-system-trust se dit, parce qu'il explique d'avance l'echec qu'il provoque")
    void strictTrustIsAnnounced() {
        Optional<String> line =
                StartupDisclosure.trustLine(TrustStores.Trust.jdkOnly(), false, null);

        assertTrue(line.isPresent());
        assertTrue(line.get().contains("magasin de la JDK seul"), line.get());
        assertTrue(line.get().contains("--no-system-trust"), line.get());
    }

    @Test
    @DisplayName("la ligne Confiance se lit apres la route, et avant le rappel")
    void theTrustLineSitsBetweenTheRouteAndTheReminder() {
        List<String> lines = StartupDisclosure.lines(Privileges.of("francky", false), DIRECT,
                systemStoreUsed(), true, "Zscaler Inc.");

        assertEquals(6, lines.size(), lines.toString());
        assertTrue(lines.get(3).startsWith("Route     :"), lines.toString());
        assertTrue(lines.get(4).startsWith("Confiance :"), lines.toString());
        assertTrue(lines.get(5).startsWith("Rappel    :"), lines.toString());
    }

    @Test
    @DisplayName("sans rien a dire, le bloc garde ses cinq lignes d'origine")
    void withNothingToSayTheBlockKeepsItsFiveLines() {
        assertEquals(5, StartupDisclosure.lines(Privileges.of("francky", false), DIRECT).size());
        assertEquals(5, StartupDisclosure.lines(Privileges.of("francky", false), DIRECT,
                TrustStores.Trust.jdkOnly(), true, null).size());
    }

    // ------------------------------------------------------------------ le canal de trafic

    @Test
    @DisplayName("le client de trafic porte le truststore additionne — WebSocket compris")
    void theTrafficClientCarriesTheMergedTrustStore() throws Exception {
        List<X509Certificate> roots = TrustStores.jdkRoots();
        Assumptions.assumeTrue(roots.size() > 3, "magasin du JDK illisible sur cette image");
        TrustStores.Trust trust = TrustStores.resolve(roots.subList(0, roots.size() - 1),
                List.of(roots.get(roots.size() - 1)), "/etc/ssl/certs/ca-certificates.crt");

        // Un seul HttpClient pour TOUT le trafic du runner : appairage, long-polling, et le
        // WebSocket, qui en dérive par newWebSocketBuilder(). Le truststore posé ici vaut donc pour
        // les trois — c'est la raison pour laquelle il n'y a rien d'autre à câbler.
        assertSame(trust.context(),
                RunnerMain.buildHttpClient(ProxyResolver.fromEnv(Map.of()), trust).sslContext());
        assertNotSame(SSLContext.getDefault(),
                RunnerMain.buildHttpClient(ProxyResolver.fromEnv(Map.of()), trust).sslContext());
    }

    @Test
    @DisplayName("en repli, le client de trafic reste sur le contexte de la JVM")
    void onFallbackTheTrafficClientKeepsTheJvmContext() throws Exception {
        assertSame(SSLContext.getDefault(),
                RunnerMain.buildHttpClient(ProxyResolver.fromEnv(Map.of()),
                        TrustStores.Trust.jdkOnly()).sslContext());
        assertSame(SSLContext.getDefault(),
                RunnerMain.buildHttpClient(ProxyResolver.fromEnv(Map.of()), null).sslContext());
    }

    /** Un contexte quelconque : ces tests-là ne regardent que ce qui est écrit, pas la confiance. */
    private static SSLContext silentContext() {
        try {
            return SSLContext.getDefault();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
