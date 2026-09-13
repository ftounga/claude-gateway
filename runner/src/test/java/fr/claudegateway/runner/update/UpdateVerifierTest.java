package fr.claudegateway.runner.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.launcher.LauncherHome;

/** F-111 / SF-111-03 — empreinte, signature, identité, avant toute écriture. */
class UpdateVerifierTest {

    private static final String ID = "1.1.0-202609200900-bbb2222";

    private KeyPair keys;
    private byte[] jar;
    private String sha;
    private String signature;
    private UpdateVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        keys = TestSigning.keyPair();
        jar = TestSigning.jarOf("1.1.0", "202609200900", "bbb2222");
        sha = LauncherHome.sha256(jar);
        signature = TestSigning.sign(keys.getPrivate(), jar);
        verifier = new UpdateVerifier(keys.getPublic());
    }

    @Test
    void accepteUnJarSigneIntactDeLaVersionDemandee() throws Exception {
        assertEquals(ID, verifier.verify(jar, sha, signature, ID).id());
        assertEquals(ID, verifier.verify(jar, sha.toUpperCase(), signature + "\n", ID).id(),
                "casse et fin de ligne ne changent rien");
    }

    @Test
    void refuseUneEmpreinteFausse() {
        UpdateRejectedException e = assertThrows(UpdateRejectedException.class,
                () -> verifier.verify(jar, "00" + sha.substring(2), signature, ID));
        assertEquals(UpdateRejectedException.SHA256_MISMATCH, e.reason());
    }

    @Test
    void refuseUnFichierModifieMemeAvecSonEmpreinte() throws Exception {
        // Ce que ferait un proxy qui intercepte TLS, ou une gateway compromise : il sert un autre fichier
        // ET l'empreinte qui va avec. Seule la signature l'arrête.
        byte[] modifie = TestSigning.jarOf("1.1.0", "202609200900", "bbb2222");
        modifie[modifie.length - 1] ^= 1;
        UpdateRejectedException e = assertThrows(UpdateRejectedException.class,
                () -> verifier.verify(modifie, LauncherHome.sha256(modifie), signature, ID));
        assertEquals(UpdateRejectedException.SIGNATURE_INVALID, e.reason());
    }

    @Test
    void refuseUneSignatureDUneAutreCleAbsenteOuIllisible() throws Exception {
        String autre = TestSigning.sign(TestSigning.keyPair().getPrivate(), jar);
        for (String faux : new String[] { autre, "", null, "pas du base64 !" }) {
            UpdateRejectedException e = assertThrows(UpdateRejectedException.class,
                    () -> verifier.verify(jar, sha, faux, ID));
            assertEquals(UpdateRejectedException.SIGNATURE_INVALID, e.reason());
        }
    }

    @Test
    void refuseUnJarSigneDUneAutreVersion() throws Exception {
        // Un ancien runner régulièrement signé, servi à la place de la version demandée.
        byte[] ancien = TestSigning.jarOf("1.0.0", "202609130000", "aaa1111");
        UpdateRejectedException e = assertThrows(UpdateRejectedException.class,
                () -> verifier.verify(ancien, LauncherHome.sha256(ancien),
                        TestSigning.sign(keys.getPrivate(), ancien), ID));
        assertEquals(UpdateRejectedException.VERSION_MISMATCH, e.reason());
    }

    @Test
    void laCleDeProductionEmbarqueeEstUneCleEd25519Inchangee() throws Exception {
        String pem;
        try (InputStream in = UpdateVerifier.class.getResourceAsStream(UpdateVerifier.PUBLIC_KEY_RESOURCE)) {
            pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
        PublicKey key = UpdateVerifier.readPublicKey(pem);

        assertEquals("EdDSA", key.getAlgorithm());
        assertEquals("MCowBQYDK2VwAyEATeAW7ciJTXUI8zdoREg43i1Uysk6qyDdF8ocE8FsmEU=",
                java.util.Base64.getEncoder().encodeToString(key.getEncoded()),
                "la clé publique de production ne se change pas dans un test");
        // Et elle refuse ce que la clé de test a signé.
        assertThrows(UpdateRejectedException.class,
                () -> UpdateVerifier.embedded().verify(jar, sha, signature, ID));
    }
}
