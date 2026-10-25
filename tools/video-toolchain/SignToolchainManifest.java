import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * F-215 / SF-215-06 — Signe le manifeste de la chaîne vidéo avec la clé de mise à jour du runner (F-111).
 *
 * <pre>
 * java SignToolchainManifest.java &lt;clé-privée.pem&gt; &lt;clé-publique.pem&gt; &lt;manifest.json&gt;
 * </pre>
 *
 * <p>Écrit {@code manifest.json.sig} (Ed25519 sur les octets EXACTS du manifeste, Base64) à côté du manifeste.
 * La signature est revérifiée avec la clé publique donnée — en production, celle embarquée dans le runner
 * ({@code runner/src/main/resources/update-signing-public-key.pem}) : une clé privée qui ne lui correspond pas
 * arrête tout, plutôt que de publier un manifeste qu'aucun runner n'accepterait.</p>
 */
public final class SignToolchainManifest {

    private SignToolchainManifest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage : java SignToolchainManifest.java <clé-privée.pem> <clé-publique.pem> <manifest.json>");
            System.exit(2);
        }
        Path manifest = Path.of(args[2]);
        byte[] bytes = Files.readAllBytes(manifest);
        PrivateKey key = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(pem(Files.readString(Path.of(args[0])))));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(bytes);
        byte[] signature = signer.sign();

        PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(pem(Files.readString(Path.of(args[1])))));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(bytes);
        if (!verifier.verify(signature)) {
            System.err.println("La clé privée ne correspond pas à la clé publique " + args[1]
                    + " : aucun runner n'accepterait ce manifeste. Rien n'est écrit.");
            System.exit(1);
        }
        Path out = manifest.resolveSibling(manifest.getFileName() + ".sig");
        Files.writeString(out, Base64.getEncoder().encodeToString(signature) + "\n", StandardCharsets.US_ASCII);
        System.out.println("Manifeste signé : " + out);
    }

    private static byte[] pem(String text) {
        return Base64.getDecoder().decode(text.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
    }
}
