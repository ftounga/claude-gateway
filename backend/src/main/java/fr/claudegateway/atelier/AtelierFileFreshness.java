package fr.claudegateway.atelier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * <b>Suivi de fraîcheur des fichiers</b> (F-121 / SF-121-19), à l'échelle du <b>fil</b> — l'écart de
 * parité « modif externe non détectée / édition à l'aveugle » de l'audit consolidé.
 *
 * <p>Là où l'aide-mémoire d'origine (F-119 / SF-119-05) ne posait qu'un <b>rappel doux</b> après une
 * édition à l'aveugle et laissait {@code write_file} écraser sans avoir lu, ce suivi mémorise, par
 * chemin <b>lu ou écrit dans le fil</b>, qu'il est « connu », et — pour un contenu <b>plein</b> vu
 * dans le tour — une <b>empreinte SHA-256</b>. Il en tire trois gestes :</p>
 * <ul>
 *   <li>une <b>garde dure</b> AVANT émission : refuser une écriture ({@code edit_file} ou
 *       {@code write_file}) sur un fichier <b>jamais lu</b> dans le fil dont l'<b>existence est
 *       prouvée</b> (l'appelant l'atteste via l'index de repo, sans aller-retour runner). Toute
 *       <b>incertitude</b> — index muet, fichier peut-être neuf — reste <b>autorisée</b> (repli sûr :
 *       ni la création d'un fichier neuf, ni un poste sans index ne sont jamais bloqués) ;</li>
 *   <li>à défaut de refus (existence non prouvée), le <b>rappel doux</b> d'origine (SF-119-05) après un
 *       {@code edit_file} d'un fichier jamais lu — jamais bloquant ;</li>
 *   <li>une <b>note</b> APRÈS une relecture <b>pleine</b> dont le contenu diffère de l'empreinte
 *       connue : « ce fichier a changé depuis ta lecture précédente ». Jamais un refus.</li>
 * </ul>
 *
 * <p><b>Gateway-First / Provider-First.</b> Le suivi ne « voit » ni ne fabrique aucun contenu : il
 * relaie au modèle la consigne « lis-le d'abord ». <b>Aucun aller-retour runner</b> : l'empreinte est
 * calculée sur le contenu déjà en main (même approche SHA-256 que {@code PromptSourceStore}), et
 * l'existence vient de l'index en base. <b>Coupe-circuit</b> : {@link #guardEnabled} à {@code false}
 * (drapeau {@code app.atelier.file-state-hints}) rend la boucle silencieuse — ni garde, ni note.</p>
 */
final class AtelierFileFreshness {

    /** La garde et la note sont-elles actives ? Câblé sur {@code app.atelier.file-state-hints}. */
    private final boolean guardEnabled;
    /** Chemins lus ou écrits dans le fil (amorcés depuis l'historique rejoué, puis tenus à jour). */
    private final Set<String> known = new HashSet<>();
    /** Empreinte SHA-256 du dernier contenu <b>plein</b> vu pour un chemin, quand elle est fiable. */
    private final Map<String, String> fingerprints = new HashMap<>();

    AtelierFileFreshness(boolean guardEnabled) {
        this.guardEnabled = guardEnabled;
    }

    boolean guardEnabled() {
        return guardEnabled;
    }

    /**
     * Amorçage : marque un chemin comme <b>connu</b> sans empreinte. Sert à rejouer la connaissance des
     * tours précédents du fil (les résultats d'historique sont bornés, donc leur empreinte ne serait pas
     * fiable — seule la présence l'est).
     */
    void seedKnown(String path) {
        if (path != null && !path.isBlank()) {
            known.add(path);
        }
    }

    /** Vrai si le chemin a été lu ou écrit dans ce fil. */
    boolean isKnown(String path) {
        return path != null && known.contains(path);
    }

    /**
     * <b>Garde AVANT émission</b> d'une écriture : rend le message de refus, ou vide pour laisser passer.
     * Ne mute rien (le chemin ne devient connu qu'après une écriture <b>aboutie</b>, cf.
     * {@link #noteWrite}). Le refus n'est prononcé que si le fichier est <b>connu comme existant</b> et
     * <b>jamais lu</b> dans le fil ; sinon, repli sûr — on laisse passer (un {@code edit_file} de repli
     * recevra plus tard le rappel doux de {@link #noteWrite}).
     *
     * @param existsOnDisk vrai <b>seulement si l'existence est prouvée</b> (index de repo) ; toute
     *                     incertitude vaut « peut-être neuf » ⇒ autorisé (repli sûr)
     */
    Optional<String> refuseWrite(String tool, String path, boolean existsOnDisk) {
        if (!guardEnabled || path == null || path.isBlank() || known.contains(path) || !existsOnDisk) {
            return Optional.empty();
        }
        if ("edit_file".equals(tool)) {
            return Optional.of("Tu n'as pas lu « " + path + " » dans ce fil : lis-le avec read_file avant "
                    + "de l'éditer. Éditer un fichier sans l'avoir lu, c'est raisonner sur un contenu supposé.");
        }
        if ("write_file".equals(tool)) {
            return Optional.of("Tu vas écraser « " + path + " » sans l'avoir lu dans ce fil : lis-le avec "
                    + "read_file avant de remplacer tout son contenu (ou vérifie que c'est bien voulu).");
        }
        return Optional.empty();
    }

    /**
     * Enregistre une <b>lecture aboutie</b> : le chemin devient connu. Rend une note de changement si une
     * relecture <b>pleine</b> (non paginée) diffère de l'empreinte connue ; jamais un refus.
     *
     * @param paginated vrai pour une lecture partielle ({@code offset}/{@code limit}) : l'empreinte
     *                  d'une tranche n'est pas comparable au fichier entier, donc on ne compare pas
     */
    Optional<String> noteRead(String path, String content, boolean paginated) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        boolean wasKnown = known.contains(path);
        known.add(path);
        if (paginated || content == null) {
            return Optional.empty();
        }
        String digest = sha256(content);
        String previous = fingerprints.put(path, digest);
        if (wasKnown && previous != null && !previous.equals(digest)) {
            return Optional.of("Note : « " + path + " » a changé depuis ta lecture précédente dans ce fil ; "
                    + "tu en reçois ici la version à jour.");
        }
        return Optional.empty();
    }

    /**
     * Enregistre une <b>écriture aboutie</b> : le chemin devient connu et frais. Pour {@code write_file},
     * le contenu écrit est connu en entier — on en garde l'empreinte ; pour {@code edit_file}, le fichier
     * complet n'est pas en main (seul le remplacement l'est) — on <b>oublie</b> l'empreinte, si bien
     * qu'une relecture ultérieure rétablira une base fiable sans fausse alerte.
     *
     * <p>Rend le <b>rappel doux</b> de lecture-avant-édition (F-119 / SF-119-05) quand un
     * {@code edit_file} a modifié un fichier <b>jamais lu</b> et que la garde dure ne l'a pas refusé
     * (existence non prouvée) — jamais bloquant.</p>
     */
    Optional<String> noteWrite(String tool, String path, String content, boolean fullContentKnown) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        boolean wasKnown = known.contains(path);
        known.add(path);
        if (fullContentKnown && content != null) {
            fingerprints.put(path, sha256(content));
        } else {
            fingerprints.remove(path);
        }
        if (guardEnabled && !wasKnown && "edit_file".equals(tool)) {
            return Optional.of("Rappel : tu as modifié « " + path + " » sans l'avoir lu dans ce fil. "
                    + "Relis-le avant de l'éditer si tu n'es pas sûr de son contenu.");
        }
        return Optional.empty();
    }

    private static String sha256(String content) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 est garanti par la plateforme : ce chemin n'existe pas en pratique. On rend une
            // empreinte de repli non nulle plutôt que d'échouer un tour.
            return Integer.toHexString(content.hashCode());
        }
    }
}
