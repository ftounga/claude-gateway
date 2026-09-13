package fr.claudegateway.governance;

import java.util.ArrayList;
import java.util.List;

/**
 * Le <b>registre des empreintes publiées</b> d'un chemin de paquet (F-96 / SF-96-02).
 *
 * <p><b>Pourquoi il existe.</b> SF-96-01 reconnaît un artefact intact à l'empreinte de ce qu'on lui
 * avait déposé. Un poste activé <b>avant</b> F-96 n'en a aucune : tous ses fichiers retomberaient en
 * « modifié localement », et la mise à jour ne toucherait que les postes nés après elle — laissant
 * exactement les postes existants dans l'état que F-96 devait corriger.</p>
 *
 * <p><b>Il y a une deuxième façon de savoir qu'un fichier est le nôtre</b> : son contenu est
 * <i>exactement</i> l'un de ceux que le produit a publiés à ce chemin. Un tel fichier n'a, par
 * construction, été touché par personne — c'est mot pour mot une version que le produit a écrite. Le
 * registre retient donc les empreintes des contenus <b>antérieurs</b>, les plus récentes d'abord.</p>
 *
 * <p><b>Des empreintes, pas des contenus</b> : il sert à <b>reconnaître</b>, jamais à restaurer. Et
 * il est borné — au-delà de {@link #MAX}, un contenu d'il y a vingt versions n'est plus reconnu, donc
 * <b>conservé</b> : la borne se trompe toujours du côté prudent.</p>
 */
public final class GovernanceKnownDigests {

    /** Nombre maximal d'empreintes retenues par chemin, les plus récentes d'abord. */
    public static final int MAX = 20;

    /** Longueur de la colonne qui stocke le registre : {@link #MAX} empreintes et leurs séparateurs. */
    public static final int MAX_LENGTH = MAX * (GovernanceDigest.LENGTH + 1);

    private GovernanceKnownDigests() {
    }

    /** Les empreintes retenues, dans l'ordre stocké. Jamais {@code null}. */
    public static List<String> parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        List<String> digests = new ArrayList<>();
        for (String line : stored.split("\n")) {
            String digest = line.trim();
            if (!digest.isEmpty() && !digests.contains(digest)) {
                digests.add(digest);
            }
        }
        return List.copyOf(digests);
    }

    /** La forme stockée, ou {@code null} si le registre est vide — une chaîne vide ne dit rien. */
    public static String join(List<String> digests) {
        return digests == null || digests.isEmpty() ? null : String.join("\n", digests);
    }

    /**
     * Fusionne des registres, <b>dans l'ordre des arguments</b> : le premier est le plus récent.
     *
     * <p>Les doublons et les valeurs vides tombent, et le résultat est coupé à {@link #MAX} — ce sont
     * les <b>plus récentes</b> qui survivent, parce qu'un poste en retard d'une version est bien plus
     * probable qu'un poste en retard de vingt.</p>
     */
    @SafeVarargs
    public static List<String> merge(List<String>... registers) {
        List<String> merged = new ArrayList<>();
        for (List<String> register : registers) {
            if (register == null) {
                continue;
            }
            for (String digest : register) {
                if (digest == null || digest.isBlank() || merged.contains(digest)) {
                    continue;
                }
                merged.add(digest.trim());
                if (merged.size() >= MAX) {
                    return List.copyOf(merged);
                }
            }
        }
        return List.copyOf(merged);
    }
}
