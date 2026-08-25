package fr.claudegateway.atelier;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Instructions portées par un projet (F-34 / SF-34-01) : le contenu du fichier d'instructions du
 * workspace, déjà borné, et le chemin d'où il vient.
 *
 * <p>Le fichier lu est {@code CLAUDE.md} à la racine, avec repli sur {@code .atelier/instructions.md}
 * (décision D1 du cadrage F-34) : les dépôts ouverts depuis F-31 en portent souvent déjà un, donc le
 * gain est immédiat sans que l'utilisateur ait rien à faire.</p>
 *
 * @param path      chemin relatif du fichier retenu
 * @param content   contenu à injecter, déjà borné (mention de troncature comprise)
 * @param truncated vrai si le fichier dépassait la borne et a été coupé
 */
public record ProjectInstructions(String path, String content, boolean truncated) {

    /**
     * Chemins candidats, <b>par ordre de priorité</b>. Le premier présent gagne : deux fichiers
     * concurrents ne sont jamais concaténés, ce qui rendrait la source des consignes illisible.
     */
    public static final List<String> CANDIDATE_PATHS = List.of("CLAUDE.md", ".atelier/instructions.md");

    /**
     * Chemin d'instructions présent dans une arborescence donnée, sans lire aucun contenu.
     *
     * <p>Sert à l'affichage (le détail d'un workspace porte déjà son arborescence) : dire à l'écran
     * qu'un projet a des instructions ne doit coûter ni lecture de stockage, ni appel à GitHub.</p>
     *
     * @param files chemins relatifs des fichiers du projet
     * @return le chemin retenu, ou vide si le projet n'en porte aucun
     */
    public static Optional<String> detectPath(Collection<String> files) {
        if (files == null) {
            return Optional.empty();
        }
        return CANDIDATE_PATHS.stream().filter(files::contains).findFirst();
    }
}
