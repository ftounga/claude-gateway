package fr.claudegateway.governance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * L'<b>empreinte</b> d'un fichier déposé (F-96 / SF-96-01).
 *
 * <p><b>Pourquoi elle existe.</b> Le dépôt doit savoir si le fichier présent sur la machine est
 * <b>celui qu'on y avait mis</b>. C'est la seule question qui autorise — ou interdit — une mise à
 * jour : un artefact resté intact peut être remplacé, un fichier touché par l'utilisateur
 * <b>redevient du contenu utilisateur</b> et n'est plus jamais écrasé. Retenir l'empreinte de ce
 * qu'on a écrit est la façon la moins intrusive de répondre : pas de marqueur dans le fichier, pas
 * de copie du contenu dans la base, et le contenu de l'utilisateur ne voyage nulle part.</p>
 *
 * <p><b>Les fins de ligne sont normalisées avant de hacher</b>, et ce n'est pas un détail : un
 * runner Windows peut réécrire {@code \n} en {@code \r\n} sans que personne n'ait touché au
 * fichier. Une empreinte qui s'en émeut classerait un fichier intact comme « modifié localement » —
 * sans danger (on conserve), mais toute mise à jour deviendrait impossible sur un poste Windows,
 * c'est-à-dire précisément là où F-96 doit fonctionner.</p>
 *
 * <p><b>Rien d'autre n'est normalisé.</b> Pas d'espaces en fin de ligne, pas de casse, pas de
 * ligne finale ajoutée : au-delà des fins de ligne, une différence est une modification, et
 * l'ignorer reviendrait à écraser un travail au motif qu'il se voit peu.</p>
 */
public final class GovernanceDigest {

    /** Longueur d'une empreinte rendue : sha-256 en hexadécimal. */
    public static final int LENGTH = 64;

    private GovernanceDigest() {
    }

    /**
     * L'empreinte sha-256 d'un contenu, fins de ligne normalisées.
     *
     * @param content le contenu, tel qu'il a été lu ou tel qu'il sera écrit
     * @return 64 caractères hexadécimaux, ou {@code null} si le contenu est {@code null} — un
     *         contenu absent n'a pas d'empreinte, et une empreinte inventée autoriserait une
     *         écriture
     */
    public static String of(String content) {
        if (content == null) {
            return null;
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(sha256.digest(normalize(content).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 est exigé de toute JVM : si elle manque, ce n'est pas un cas métier.
            throw new IllegalStateException("SHA-256 indisponible sur cette JVM.", ex);
        }
    }

    /** Vrai si les deux contenus sont le même fichier, aux fins de ligne près. */
    public static boolean sameContent(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return normalize(a).equals(normalize(b));
    }

    /** Fins de ligne ramenées à {@code \n} — Windows et vieux Mac compris. */
    static String normalize(String content) {
        return content.replace("\r\n", "\n").replace('\r', '\n');
    }
}
