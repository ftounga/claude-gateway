package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F-80 / SF-80-02 — le truststore du runner : {@code cacerts} de la JDK <b>plus</b> magasin du
 * système.
 *
 * <p>Deux exigences se tiennent la main ici. La première est de <b>reconnaître ce que le poste
 * reconnaît</b> : sans elle, le runner échoue seul là où le navigateur et {@code curl} passent. La
 * seconde est d'<b>additionner, jamais remplacer</b> : une racine publique retirée du magasin d'un
 * poste ne doit pas cesser d'être reconnue.</p>
 *
 * <p>Et une troisième, qui n'en est pas moins dure : un magasin absent ou illisible est un cas
 * <b>normal</b>. Il produit un repli silencieux, jamais une erreur.</p>
 *
 * <p><b>Où l'on trouve des certificats de test.</b> Fabriquer une autorité demanderait une
 * dépendance de plus. Le {@code cacerts} de la JDK en contient des centaines, tous réels : on en
 * met une partie du côté « JDK » et une autre du côté « poste ». La fusion ne regarde que des
 * empreintes — elle ne sait pas, et n'a pas à savoir, d'où vient un certificat.</p>
 */
class TrustStoresTest {

    private static final String SOURCE = "/etc/ssl/certs/ca-certificates.crt";

    @Test
    @DisplayName("une racine que le poste seul connait est ajoutee, comptee, et reconnue")
    void aRootKnownOnlyToTheHostIsAdded() throws Exception {
        List<X509Certificate> roots = realRoots();
        List<X509Certificate> jdk = roots.subList(0, roots.size() - 1);
        X509Certificate onlyOnThisHost = roots.get(roots.size() - 1);

        TrustStores.Trust trust = TrustStores.resolve(jdk, List.of(onlyOnThisHost), SOURCE);

        assertTrue(trust.systemStoreUsed(), "le magasin du système a apporté quelque chose");
        assertEquals(1, trust.addedRoots());
        assertNotNull(trust.context());
        assertEquals(SOURCE, trust.source());

        List<String> accepted = fingerprints(List.of(trust.manager().getAcceptedIssuers()));
        assertTrue(accepted.contains(fingerprint(onlyOnThisHost)),
                "la racine du poste doit être reconnue — c'est toute la subfeature");
    }

    @Test
    @DisplayName("ADDITIONNER, JAMAIS REMPLACER : aucune racine du cacerts ne disparait")
    void everyJdkRootSurvivesTheMerge() throws Exception {
        List<X509Certificate> roots = realRoots();
        List<X509Certificate> jdk = roots.subList(0, roots.size() - 1);
        X509Certificate onlyOnThisHost = roots.get(roots.size() - 1);

        TrustStores.Trust trust = TrustStores.resolve(jdk, List.of(onlyOnThisHost), SOURCE);

        List<String> accepted = fingerprints(List.of(trust.manager().getAcceptedIssuers()));
        for (X509Certificate jdkRoot : jdk) {
            assertTrue(accepted.contains(fingerprint(jdkRoot)),
                    "une racine du cacerts a disparu : on a remplacé au lieu d'additionner (D1)");
        }
        // D1, dit dans l'autre sens : un poste qui aurait RETIRÉ une racine publique de son magasin
        // ne doit pas faire cesser de la reconnaître. Ici le « magasin du poste » n'en contient
        // qu'une seule, et les autres sont pourtant toutes là.
        assertTrue(accepted.size() >= jdk.size() + 1);
    }

    @Test
    @DisplayName("une racine presente des deux cotes n'est comptee qu'une fois")
    void aRootPresentOnBothSidesIsCountedOnce() {
        List<X509Certificate> roots = realRoots();

        TrustStores.Trust trust =
                TrustStores.resolve(roots, List.of(roots.get(0), roots.get(1)), SOURCE);

        assertEquals(0, trust.addedRoots(), "un doublon n'ajoute rien");
        assertFalse(trust.systemStoreUsed(), "sans apport, il n'y a rien à annoncer");
        assertNull(trust.context(), "sans apport, on ne pose aucun contexte");
    }

    @Test
    @DisplayName("aucune source systeme : repli silencieux, jamais une erreur")
    void noSystemSourceFallsBackSilently() {
        TrustStores.Trust trust = TrustStores.resolve(realRoots(), List.of(), SOURCE);

        assertFalse(trust.systemStoreUsed());
        assertEquals(0, trust.addedRoots());
        assertNull(trust.context(), "le repli laisse la JVM décider, exactement comme avant");
        assertTrue(trust.source().isEmpty());
        // Et la même chose avec des entrées franchement cassées : toujours pas d'exception.
        assertNull(TrustStores.resolve(realRoots(), null, SOURCE).context());
        assertNull(TrustStores.resolve(List.of(), List.of(), SOURCE).context());
        assertNull(TrustStores.resolve(null, List.of(), SOURCE).context());
    }

    @Test
    @DisplayName("un faisceau PEM illisible n'ajoute rien et ne leve pas")
    void anUnreadableBundleAddsNothing(@TempDir Path dir) throws Exception {
        Path noise = dir.resolve("ca-certificates.crt");
        Files.write(noise, "ceci n'est pas un certificat\n".getBytes(StandardCharsets.UTF_8));

        assertTrue(TrustStores.fromPemBundle(noise).isEmpty());
        assertTrue(TrustStores.fromPemBundle(dir.resolve("absent.crt")).isEmpty());
        assertTrue(TrustStores.fromPemBundle(null).isEmpty());
    }

    @Test
    @DisplayName("un faisceau PEM concatene est lu en entier — la forme de /etc/ssl/certs")
    void aConcatenatedPemBundleIsReadWhole(@TempDir Path dir) throws Exception {
        List<X509Certificate> roots = realRoots();

        StringBuilder pem = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            pem.append(toPem(roots.get(i)));
        }
        Path bundle = dir.resolve("ca-certificates.crt");
        Files.write(bundle, pem.toString().getBytes(StandardCharsets.US_ASCII));

        assertEquals(3, TrustStores.fromPemBundle(bundle).size());
    }

    @Test
    @DisplayName("--no-system-trust n'ouvre meme pas le magasin du systeme")
    void strictTrustNeverOpensTheSystemStore() {
        TrustStores.Trust trust = TrustStores.resolve(false);

        assertFalse(trust.systemStoreUsed());
        assertNull(trust.context(), "la confiance stricte est celle de la JVM, inchangée");
        assertEquals(0, trust.addedRoots());
    }

    @Test
    @DisplayName("la confiance additionnee reste une confiance : l'inconnu est toujours refuse")
    void addedTrustStillRefusesTheUnknown() {
        List<X509Certificate> roots = realRoots();
        Assumptions.assumeTrue(roots.size() > 5, "pas assez de racines pour bâtir le cas");

        // Un truststore VOLONTAIREMENT étroit : trois racines « JDK » et une « du poste ». Tout ce
        // qui n'y est pas doit être refusé — sans quoi la confiance additionnée ne vérifierait plus
        // rien, et c'est exactement ce qui est hors périmètre de F-80.
        X509Certificate addedByTheHost = roots.get(3);
        TrustStores.Trust trust =
                TrustStores.resolve(roots.subList(0, 3), List.of(addedByTheHost), SOURCE);

        X509Certificate[] unknown = { roots.get(roots.size() - 1) };
        assertThrows(Exception.class, () -> trust.manager().checkServerTrusted(unknown, "RSA"),
                "une autorité absente des DEUX magasins doit être refusée");

        // Et la contrepartie, qui est la feature elle-même : la racine venue du poste, elle, passe.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> trust.manager().checkServerTrusted(
                        new X509Certificate[] { addedByTheHost }, "RSA"),
                "la racine que le poste seul connaît doit être acceptée");
    }

    @Test
    @DisplayName("la lecture du magasin systeme ne leve jamais, quel que soit le poste")
    void readingTheSystemStoreNeverThrows() {
        // Sur la machine qui exécute ce test, il y a peut-être /etc/ssl/certs, peut-être rien du
        // tout — c'est même le cas d'un conteneur minimal. Les deux issues sont acceptables ; ce qui
        // ne l'est pas, c'est une exception (D4).
        assertNotNull(TrustStores.systemCertificates());
        assertNotNull(TrustStores.jdkRoots());
    }

    // ------------------------------------------------------------------ outillage

    /** Des certificats réels, pris dans le magasin de la JDK qui exécute ces tests. */
    private static List<X509Certificate> realRoots() {
        List<X509Certificate> roots = TrustStores.jdkRoots();
        Assumptions.assumeTrue(roots.size() > 3,
                "magasin du JDK illisible ou trop pauvre sur cette image");
        return roots;
    }

    private static List<String> fingerprints(List<X509Certificate> certificates) throws Exception {
        List<String> prints = new ArrayList<>();
        for (X509Certificate certificate : certificates) {
            prints.add(fingerprint(certificate));
        }
        return prints;
    }

    private static String fingerprint(X509Certificate certificate) throws Exception {
        return Base64.getEncoder().encodeToString(certificate.getEncoded());
    }

    private static String toPem(X509Certificate certificate) throws Exception {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(certificate.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n";
    }
}
