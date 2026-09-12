package fr.claudegateway.runner;

/**
 * Fournit les outils <b>attachés au projet</b> d'un appel (F-48 / SF-48-02, revu par F-73).
 *
 * <p>Le runner est lancé à la racine du <b>poste</b> ; chaque appel dit sur quel <b>projet</b> il
 * travaille. Ce projet détermine le <b>dossier de départ</b> des outils — où {@code bash} démarre,
 * d'où part le listage, sous quoi se résolvent les chemins relatifs. Depuis F-73, ce n'est plus une
 * <b>borne</b> : rien n'empêche un tour d'atteindre un autre dossier de la machine.</p>
 *
 * <p>Un port, et non une classe concrète, pour une raison unique : l'aiguilleur de trames doit rester
 * vérifiable sans arborescence réelle. La mise en œuvre est {@link ProjectScopes}.</p>
 */
@FunctionalInterface
public interface ToolScopes {

    /**
     * Outils dont le dossier de départ est celui de ce projet.
     *
     * @param project chemin relatif à la racine du poste ; {@code null} ou vide = la racine
     * @throws ToolException {@code path_outside_root} si la valeur reçue sort de la racine du poste,
     *                       {@code not_found} si le dossier n'existe pas,
     *                       {@code invalid_input} s'il est malformé
     */
    ToolExecutor forProject(String project);

    /**
     * Portée <b>unique</b> : les mêmes outils quel que soit le projet demandé. Réservé aux tests et
     * aux chemins de montage historiques — en service, c'est toujours {@link ProjectScopes} qui
     * répond.
     */
    static ToolScopes fixed(ToolExecutor tools) {
        return project -> tools;
    }
}
