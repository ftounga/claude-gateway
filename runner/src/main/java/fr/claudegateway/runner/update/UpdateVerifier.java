package fr.claudegateway.runner.update;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;

import fr.claudegateway.runner.RunnerBuild;
import fr.claudegateway.runner.launcher.LauncherHome;

/**
 * La vérification d'une version téléchargée (F-111 / SF-111-03), <b>avant toute écriture</b>.
 *
 * <p>Trois contrôles, dans cet ordre :</p>
 * <ol>
 *   <li><b>empreinte</b> SHA-256 du fichier reçu = empreinte annoncée — un téléchargement tronqué ou
 *       altéré en route s'arrête là ;</li>
 *   <li><b>signature Ed25519</b> du fichier, vérifiée avec la <b>clé publique embarquée</b> dans ce
 *       runner : un proxy qui intercepte TLS, ou une gateway compromise, peut servir n'importe quel
 *       fichier et n'importe quelle empreinte — pas une signature valide ;</li>
 *   <li><b>identité</b> : l'identifiant écrit dans le jar signé = celui demandé. Sans ce contrôle, un
 *       jar ancien, régulièrement signé, pourrait être servi à la place d'une version récente.</li>
 * </ol>
 */
public final class UpdateVerifier {

    /** Clé publique de production, embarquée dans le runner. */
    public static final String PUBLIC_KEY_RESOURCE = "/update-signing-public-key.pem";

    private final PublicKey publicKey;

    public UpdateVerifier(PublicKey publicKey) {
        this.publicKey = publicKey;
    }

    /** Le vérificateur de production, sur la clé embarquée. */
    public static UpdateVerifier embedded() {
        try (InputStream in = UpdateVerifier.class.getResourceAsStream(PUBLIC_KEY_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Clé publique de mise à jour absente du runner.");
            }
            return new UpdateVerifier(readPublicKey(new String(in.readAllBytes(), StandardCharsets.US_ASCII)));
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Clé publique de mise à jour illisible.", e);
        }
    }

    /** Lit une clé publique Ed25519 au format PEM ({@code BEGIN PUBLIC KEY}). */
    public static PublicKey readPublicKey(String pem) throws GeneralSecurityException {
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }

    /**
     * Vérifie une version téléchargée.
     *
     * @param jar             les octets reçus
     * @param expectedSha256  l'empreinte annoncée (hexadécimal), jamais nulle
     * @param signatureBase64 la signature servie (Base64)
     * @param expectedId      l'identifiant de version demandé
     * @return la construction lue dans le jar vérifié
     */
    public RunnerBuild verify(byte[] jar, String expectedSha256, String signatureBase64, String expectedId)
            throws UpdateRejectedException {
        String actual = LauncherHome.sha256(jar);
        if (expectedSha256 == null || !actual.equalsIgnoreCase(expectedSha256.trim())) {
            throw new UpdateRejectedException(UpdateRejectedException.SHA256_MISMATCH,
                    "l'empreinte du fichier reçu ne correspond pas à celle annoncée (fichier altéré ou "
                            + "tronqué en route)");
        }
        if (!signatureValid(jar, signatureBase64)) {
            throw new UpdateRejectedException(UpdateRejectedException.SIGNATURE_INVALID,
                    "la signature du fichier reçu n'est pas valide pour la clé de ce runner : rien n'est "
                            + "installé");
        }
        RunnerBuild build = buildOf(jar);
        if (build == null || !build.id().equals(expectedId)) {
            throw new UpdateRejectedException(UpdateRejectedException.VERSION_MISMATCH,
                    "le fichier signé reçu est celui de la version " + (build == null ? "inconnue" : build.id())
                            + ", pas de " + expectedId);
        }
        return build;
    }

    private boolean signatureValid(byte[] jar, String signatureBase64) {
        if (signatureBase64 == null || signatureBase64.isBlank()) {
            return false;
        }
        try {
            byte[] signature = Base64.getDecoder().decode(signatureBase64.trim());
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(jar);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** La construction écrite dans un jar, ou {@code null} s'il n'en porte pas. */
    static RunnerBuild buildOf(byte[] jar) {
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().equals("runner-build.properties")) {
                    return RunnerBuild.read(new ByteArrayInputStream(in.readAllBytes()));
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }
}
