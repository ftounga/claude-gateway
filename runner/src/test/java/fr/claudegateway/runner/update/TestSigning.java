package fr.claudegateway.runner.update;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Outils de test de la signature (F-111 / SF-111-03) : des paires de clés <b>générées à la volée</b>,
 * jamais écrites dans le dépôt, et des jars minimaux portant une version.
 */
final class TestSigning {

    private TestSigning() {
    }

    static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    static String sign(PrivateKey key, byte[] data) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(data);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    static String publicPem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    static String privatePem(PrivateKey key) {
        return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    /** Un jar qui porte {@code runner-build.properties} pour cette version. */
    static byte[] jarOf(String version, String stamp, String commit) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry("runner-build.properties"));
            jar.write(("version=" + version + "\nstamp=" + stamp + "\ncommit=" + commit
                    + "\ncontract=1\njava=21\n").getBytes(StandardCharsets.ISO_8859_1));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("fr/claudegateway/runner/Exemple.class"));
            jar.write(new byte[] { (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE });
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }
}
